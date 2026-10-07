package com.rhl.payment.application;

import com.rhl.common.id.UuidV7;
import com.rhl.common.web.ApiException;
import com.rhl.common.web.ErrorCode;
import com.rhl.payment.PaymentServiceProperties;
import com.rhl.payment.domain.AdjustmentReason;
import com.rhl.payment.domain.Payment;
import com.rhl.payment.domain.Refund;
import com.rhl.payment.domain.RefundStatus;
import com.rhl.payment.domain.Wallet;
import com.rhl.payment.domain.WalletAdjustment;
import com.rhl.payment.infrastructure.persistence.PaymentRepository;
import com.rhl.payment.infrastructure.persistence.RefundRepository;
import com.rhl.payment.infrastructure.persistence.WalletAdjustmentRepository;
import com.rhl.payment.infrastructure.persistence.WalletEntryRepository;
import com.rhl.payment.infrastructure.persistence.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Finance corrections to a driver's wallet (BR-011, BR-012, FR-WAL): a compensating ledger line
 * with a reason, posted under the wallet lock, never taking the balance below zero (FR-WAL-009),
 * idempotent per Idempotency-Key (COM-008) and audited (BR-014).
 */
@Service
@RequiredArgsConstructor
public class WalletAdjustments {

    /** Wallets are kept per currency; v1.0 has one (README §2). */
    private static final String CURRENCY = "VND";

    private final WalletRepository wallets;
    private final WalletEntryRepository entries;
    private final WalletAdjustmentRepository adjustments;
    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final PaymentEvents events;
    private final AuditLog audit;
    private final PaymentServiceProperties properties;
    private final Clock clock;

    public record Command(long amount, AdjustmentReason reason, String note, UUID tripId, UUID refundId) {
    }

    /**
     * Posts the adjustment. Only an existing wallet can be adjusted, so a mistyped driver ID
     * fails instead of opening a wallet. A {@code REFUND_CLAWBACK} takes back at most the
     * refunded amount, from the driver of the refunded trip.
     */
    @Transactional
    public PaymentQueries.AdjustmentView adjust(UUID actorId, UUID driverId, Command command, String requestKey,
                                                String requestHash) {
        validate(command);
        Wallet wallet = wallets.findForUpdate(driverId, CURRENCY).orElseThrow(() -> ApiException.notFound("Wallet"));
        Optional<WalletAdjustment> existing = adjustments.findByWalletIdAndRequestKey(wallet.getId(), requestKey);
        if (existing.isPresent()) {
            if (!existing.get().getRequestHash().equals(requestHash)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "This Idempotency-Key was already used for a different request");
            }
            long balanceAfter = entries.findByReferenceTypeAndReferenceId(Wallet.ADJUSTMENT, existing.get().getId())
                    .orElseThrow().getBalanceAfter();
            return PaymentQueries.AdjustmentView.of(existing.get(), driverId, balanceAfter);
        }
        UUID tripId = command.tripId();
        if (command.refundId() != null) {
            tripId = checkRefund(driverId, command, tripId);
        }
        Wallet.Adjusted adjusted;
        try {
            adjusted = wallet.adjust(UuidV7.random(), UuidV7.random(), command.amount(), command.reason(),
                    blankToNull(command.note()), tripId, command.refundId(), requestKey, requestHash, actorId,
                    clock.instant());
        } catch (Wallet.NegativeBalanceException e) {
            throw ApiException.rule("The adjustment would make the wallet balance negative (balance "
                    + wallet.getBalance() + " " + wallet.getCurrency() + ")");
        }
        adjustments.saveAndFlush(adjusted.adjustment());
        entries.save(adjusted.entry());
        wallets.saveAndFlush(wallet);
        events.walletAdjusted(wallet, adjusted.adjustment());
        audit.record(actorId, "WALLET_ADJUSTED", "WALLET", wallet.getId(), "SUCCESS", delta(driverId, adjusted,
                wallet.getBalance()));
        return PaymentQueries.AdjustmentView.of(adjusted.adjustment(), driverId, wallet.getBalance());
    }

    private void validate(Command command) {
        if (command.amount() == 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "The amount cannot be zero");
        }
        long max = properties.wallet().maxAdjustment();
        if (Math.abs(command.amount()) > max) {
            throw ApiException.rule("An adjustment cannot exceed " + max + " " + CURRENCY);
        }
        if (command.reason() == AdjustmentReason.OTHER && (command.note() == null || command.note().isBlank())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "A note is required when the reason is OTHER");
        }
        if (command.reason() == AdjustmentReason.REFUND_CLAWBACK && (command.refundId() == null
                || command.amount() > 0)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "A refund clawback is a debit (negative amount) and needs refundId");
        }
    }

    /** @return the trip the adjustment is about: the refunded one */
    private UUID checkRefund(UUID driverId, Command command, UUID tripId) {
        Refund refund = refunds.findById(command.refundId()).orElseThrow(() -> ApiException.notFound("Refund"));
        Payment payment = payments.findById(refund.getPaymentId()).orElseThrow();
        if (!driverId.equals(payment.getDriverId())) {
            throw ApiException.rule("The refund is not for a trip of this driver");
        }
        if (tripId != null && !tripId.equals(refund.getTripId())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "tripId does not match the refunded trip");
        }
        if (command.reason() == AdjustmentReason.REFUND_CLAWBACK) {
            if (refund.getStatus() != RefundStatus.SUCCEEDED) {
                throw ApiException.invalidState("Only a completed refund can be clawed back");
            }
            // Earlier clawbacks of this refund commit under this same wallet lock.
            long clawedBack = -adjustments.sumByRefund(refund.getId(), AdjustmentReason.REFUND_CLAWBACK);
            if (clawedBack - command.amount() > refund.getAmount()) {
                throw ApiException.rule("At most " + (refund.getAmount() - clawedBack) + " " + refund.getCurrency()
                        + " of this refund can still be clawed back");
            }
        }
        return refund.getTripId();
    }

    /** Amounts, reason and references only: the free-text note stays out of the audit trail. */
    private static Map<String, Object> delta(UUID driverId, Wallet.Adjusted adjusted, long balance) {
        WalletAdjustment a = adjusted.adjustment();
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("adjustmentId", a.getId().toString());
        delta.put("driverId", driverId.toString());
        delta.put("amount", a.getAmount());
        delta.put("currency", a.getCurrency());
        delta.put("reason", a.getReason().name());
        delta.put("balanceAfter", balance);
        if (a.getTripId() != null) {
            delta.put("tripId", a.getTripId().toString());
        }
        if (a.getRefundId() != null) {
            delta.put("refundId", a.getRefundId().toString());
        }
        return delta;
    }

    private static String blankToNull(String note) {
        return note == null || note.isBlank() ? null : note.strip();
    }
}
