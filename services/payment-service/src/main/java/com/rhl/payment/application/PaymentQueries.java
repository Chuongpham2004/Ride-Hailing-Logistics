package com.rhl.payment.application;

import com.rhl.common.security.CurrentUser;
import com.rhl.common.security.Role;
import com.rhl.common.web.ApiException;
import com.rhl.payment.domain.Payment;
import com.rhl.payment.domain.PaymentAttempt;
import com.rhl.payment.domain.PaymentPurpose;
import com.rhl.payment.domain.PaymentStatus;
import com.rhl.payment.domain.Wallet;
import com.rhl.payment.domain.WalletEntry;
import com.rhl.payment.infrastructure.persistence.PaymentAttemptRepository;
import com.rhl.payment.infrastructure.persistence.PaymentRepository;
import com.rhl.payment.infrastructure.persistence.WalletEntryRepository;
import com.rhl.payment.infrastructure.persistence.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read side for customers, drivers and finance staff (FR-PAY, FR-WAL). */
@Service
@RequiredArgsConstructor
public class PaymentQueries {

    /** Wallets are kept per currency; v1.0 has one (README §2). */
    private static final String CURRENCY = "VND";
    private static final Role[] FINANCE = {Role.FINANCE_STAFF, Role.ADMINISTRATOR};

    private final PaymentRepository payments;
    private final PaymentAttemptRepository attempts;
    private final WalletRepository wallets;
    private final WalletEntryRepository entries;

    public record PaymentView(UUID id, UUID tripId, PaymentPurpose purpose, UUID customerId, UUID driverId,
                              long amount, String currency, PaymentStatus status, int attemptCount, String provider,
                              String providerRef, String failureCode, Instant createdAt, Instant succeededAt) {

        static PaymentView of(Payment p) {
            return new PaymentView(p.getId(), p.getTripId(), p.getPurpose(), p.getCustomerId(), p.getDriverId(),
                    p.getAmount(), p.getCurrency(), p.getStatus(), p.getAttemptCount(), p.getProvider(),
                    p.getProviderRef(), p.getFailureCode(), p.getCreatedAt(), p.getSucceededAt());
        }
    }

    public record EntryView(UUID id, String type, long amount, long balanceAfter, UUID tripId,
                            Integer commissionRuleVersion, Instant createdAt) {

        static EntryView of(WalletEntry e) {
            return new EntryView(e.getId(), e.getEntryType(), e.getAmount(), e.getBalanceAfter(), e.getTripId(),
                    e.getCommissionRuleVersion(), e.getCreatedAt());
        }
    }

    /** @param nextBefore pass as {@code before} for the next page; {@code null} on the last one */
    public record WalletView(UUID walletId, UUID driverId, long balance, String currency, List<EntryView> entries,
                             UUID nextBefore) {
    }

    /** The customer who owes it, or finance staff; anyone else gets 404 so IDs cannot be probed. */
    @Transactional(readOnly = true)
    public PaymentView payment(CurrentUser user, UUID paymentId) {
        return payments.findById(paymentId)
                .filter(p -> p.getCustomerId().equals(user.id()) || user.hasAny(FINANCE))
                .map(PaymentView::of)
                .orElseThrow(() -> ApiException.notFound("Payment"));
    }

    @Transactional(readOnly = true)
    public List<PaymentView> forTrip(CurrentUser user, UUID tripId) {
        return payments.findByTripIdOrderByCreatedAt(tripId).stream()
                .filter(p -> p.getCustomerId().equals(user.id()) || user.hasAny(FINANCE))
                .map(PaymentView::of)
                .toList();
    }

    public record AttemptView(int attemptNo, PaymentStatus status, String provider, String providerRef,
                              String failureCode, Instant createdAt, Instant completedAt) {

        static AttemptView of(PaymentAttempt a) {
            return new AttemptView(a.getAttemptNo(), a.getStatus(), a.getProvider(), a.getProviderRef(),
                    a.getFailureCode(), a.getCreatedAt(), a.getCompletedAt());
        }
    }

    public record PaymentPage(List<PaymentView> items, UUID nextBefore) {
    }

    /** Finance staff: every payment, newest first, optionally only one status (FR-ADM, NFR-PERF-008). */
    @Transactional(readOnly = true)
    public PaymentPage list(PaymentStatus status, UUID before, int limit) {
        List<Payment> page = payments.findPage(status == null ? null : status.name(), before, limit);
        UUID next = page.size() == limit ? page.getLast().getId() : null;
        return new PaymentPage(page.stream().map(PaymentView::of).toList(), next);
    }

    /** Finance staff: every provider attempt of a payment, oldest first. */
    @Transactional(readOnly = true)
    public List<AttemptView> attempts(UUID paymentId) {
        if (!payments.existsById(paymentId)) {
            throw ApiException.notFound("Payment");
        }
        return attempts.findByPaymentIdOrderByAttemptNo(paymentId).stream().map(AttemptView::of).toList();
    }

    /** The caller's own wallet, newest ledger lines first; an empty wallet before the first earning. */
    @Transactional(readOnly = true)
    public WalletView wallet(UUID driverId, UUID before, int limit) {
        Wallet wallet = wallets.findByDriverIdAndCurrency(driverId, CURRENCY).orElse(null);
        if (wallet == null) {
            return new WalletView(null, driverId, 0, CURRENCY, List.of(), null);
        }
        List<WalletEntry> page = before == null ? entries.findPage(wallet.getId(), limit)
                : entries.findPage(wallet.getId(), before, limit);
        UUID next = page.size() == limit ? page.getLast().getId() : null;
        return new WalletView(wallet.getId(), driverId, wallet.getBalance(), wallet.getCurrency(),
                page.stream().map(EntryView::of).toList(), next);
    }
}
