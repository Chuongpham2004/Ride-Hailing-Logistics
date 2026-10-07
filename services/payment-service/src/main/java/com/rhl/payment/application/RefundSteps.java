package com.rhl.payment.application;

import com.rhl.common.id.UuidV7;
import com.rhl.common.web.ApiException;
import com.rhl.common.web.ErrorCode;
import com.rhl.payment.domain.Payment;
import com.rhl.payment.domain.PaymentStatus;
import com.rhl.payment.domain.Refund;
import com.rhl.payment.domain.RefundReason;
import com.rhl.payment.infrastructure.persistence.PaymentRepository;
import com.rhl.payment.infrastructure.persistence.RefundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Transactional steps around a refund sent to the provider (FR-PAY): open it under the payment
 * row lock, then record the provider's answer. The call itself happens between the two, outside
 * any transaction. Lock order is payment, then refund.
 */
@Service
@RequiredArgsConstructor
public class RefundSteps {

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PaymentEvents events;
    private final PaymentProvider provider;
    private final AuditLog audit;
    private final Clock clock;

    /** @param created {@code false} when the same request was already accepted under this key */
    public record Requested(UUID refundId, boolean created) {
    }

    /**
     * Finance staff refund a captured payment, fully ({@code amount == null}: what is left) or in
     * part. The total refunded never exceeds the captured amount (BR-010): the check runs under
     * the payment lock and is enforced again by the database. The same Idempotency-Key with the
     * same request returns the refund already opened (COM-008).
     */
    @Transactional
    public Requested request(UUID actorId, UUID paymentId, Long amount, RefundReason reason, String note,
                             String requestKey, String requestHash) {
        if (reason == RefundReason.OTHER && (note == null || note.isBlank())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "A note is required when the reason is OTHER");
        }
        Payment payment = payments.findByIdForUpdate(paymentId).orElseThrow(() -> ApiException.notFound("Payment"));
        Optional<Refund> existing = refunds.findByPaymentIdAndRequestKey(paymentId, requestKey);
        if (existing.isPresent()) {
            if (!existing.get().getRequestHash().equals(requestHash)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "This Idempotency-Key was already used for a different request");
            }
            return new Requested(existing.get().getId(), false);
        }
        if (payment.getStatus() == PaymentStatus.REFUND_PENDING) {
            throw ApiException.invalidState("A refund of this payment is still in progress; try again once it has "
                    + "completed");
        }
        if (!payment.getStatus().isCaptured()) {
            throw ApiException.invalidState("Only a paid payment can be refunded");
        }
        long refundable = payment.refundable();
        if (refundable == 0) {
            throw ApiException.rule("This payment was already refunded in full");
        }
        long value = amount == null ? refundable : amount;
        if (value <= 0 || value > refundable) {
            throw ApiException.rule("The refund amount must be between 1 and " + refundable + " "
                    + payment.getCurrency());
        }
        Instant now = clock.instant();
        Refund refund = payment.startRefund(UuidV7.random(), value, reason, blankToNull(note), provider.name(),
                requestKey, requestHash, actorId, now);
        payments.saveAndFlush(payment);
        refunds.saveAndFlush(refund);
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("refundId", refund.getId().toString());
        delta.put("amount", value);
        delta.put("currency", refund.getCurrency());
        delta.put("reason", reason.name());
        delta.put("refundableBefore", refundable);
        audit.record(actorId, "REFUND_REQUESTED", "PAYMENT", paymentId, "SUCCESS", delta);
        return new Requested(refund.getId(), true);
    }

    /** What to send to the provider for a refund whose outcome is not known yet. */
    @Transactional(readOnly = true)
    public Optional<PaymentProvider.RefundRequest> providerRequest(UUID refundId) {
        return refunds.findById(refundId)
                .filter(Refund::isPending)
                .map(refund -> {
                    Payment payment = payments.findById(refund.getPaymentId()).orElseThrow();
                    return new PaymentProvider.RefundRequest(refund.getIdempotencyKey(), payment.getProviderRef(),
                            refund.getAmount(), refund.getCurrency(), refund.getReason().name());
                });
    }

    /**
     * Records the provider's answer, from the synchronous call or a signed callback. Idempotent
     * like {@link PaymentSteps#complete}: a refund already settled is left as it is, so
     * {@code RefundCompleted} is written once.
     *
     * @return whether this call settled the refund
     */
    @Transactional
    public boolean complete(UUID refundId, PaymentProvider.ChargeResult result) {
        UUID paymentId = refunds.findPaymentId(refundId).orElseThrow();
        Payment payment = payments.findByIdForUpdate(paymentId).orElseThrow();
        // Refunds only change under this lock, so this read is current.
        Refund refund = refunds.findById(refundId).orElseThrow();
        if (!refund.isPending()) {
            return false;
        }
        Instant now = clock.instant();
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("paymentId", paymentId.toString());
        delta.put("amount", refund.getAmount());
        switch (result.outcome()) {
            case PENDING -> {
                refund.accepted(result.reference());
                refunds.saveAndFlush(refund);
                return false;
            }
            case DECLINED -> {
                payment.refundFailed(refund, result.failureCode(), now);
                payments.saveAndFlush(payment);
                refunds.saveAndFlush(refund);
                delta.put("failureCode", String.valueOf(result.failureCode()));
                audit.record(null, "REFUND_SETTLED", "REFUND", refundId, "FAILURE", delta);
                return true;
            }
            case SUCCEEDED -> {
                // handled below
            }
        }
        payment.refunded(refund, result.reference(), now);
        payments.saveAndFlush(payment);
        refunds.saveAndFlush(refund);
        events.refundCompleted(payment, refund);
        delta.put("refundedTotal", payment.getRefundedAmount());
        audit.record(null, "REFUND_SETTLED", "REFUND", refundId, "SUCCESS", delta);
        return true;
    }

    private static String blankToNull(String note) {
        return note == null || note.isBlank() ? null : note.strip();
    }
}
