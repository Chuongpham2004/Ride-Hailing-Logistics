package com.rhl.payment.application;

import com.rhl.common.id.UuidV7;
import com.rhl.common.messaging.EventEnvelope;
import com.rhl.common.messaging.ProcessedEvents;
import com.rhl.common.web.ApiException;
import com.rhl.payment.domain.CommissionRule;
import com.rhl.payment.domain.Payment;
import com.rhl.payment.domain.PaymentAttempt;
import com.rhl.payment.domain.PaymentPurpose;
import com.rhl.payment.domain.PaymentStatus;
import com.rhl.payment.domain.ServiceType;
import com.rhl.payment.domain.Wallet;
import com.rhl.payment.domain.WalletEntry;
import com.rhl.payment.infrastructure.persistence.CommissionRuleRepository;
import com.rhl.payment.infrastructure.persistence.PaymentAttemptRepository;
import com.rhl.payment.infrastructure.persistence.PaymentRepository;
import com.rhl.payment.infrastructure.persistence.WalletEntryRepository;
import com.rhl.payment.infrastructure.persistence.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Transactional steps around a provider call (README §4.9): open the payment and its first
 * attempt, then record the provider's answer. The call itself happens between the two, outside
 * any transaction. Lock order is payment, then wallet.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentSteps {

    static final String CONSUMER = "payment-service.charges";

    private final ProcessedEvents processedEvents;
    private final PaymentRepository payments;
    private final PaymentAttemptRepository attempts;
    private final CommissionRuleRepository commissionRules;
    private final WalletRepository wallets;
    private final WalletEntryRepository entries;
    private final PaymentEvents events;
    private final PaymentProvider provider;
    private final Clock clock;

    /**
     * Opens the payment for a settled trip: {@code FareFinalized} or a non-zero
     * {@code CancellationFeeCalculated}. Once per trip and purpose (BR-015).
     *
     * @return the attempt to send to the provider; empty when there is nothing (more) to charge
     */
    @Transactional
    public Optional<UUID> open(EventEnvelope event) {
        if (!processedEvents.markProcessed(CONSUMER, event.eventId())) {
            return Optional.empty();
        }
        JsonNode p = event.payload();
        boolean fare = "FareFinalized".equals(event.eventType());
        PaymentPurpose purpose = fare ? PaymentPurpose.TRIP_FARE : PaymentPurpose.CANCELLATION_FEE;
        long amount = fare ? p.path("total").asLong() : p.path("fee").asLong();
        UUID tripId = id(p, "tripId");
        if (amount <= 0) {
            return Optional.empty(); // a free cancellation: nothing is owed
        }
        if (payments.existsByTripIdAndPurpose(tripId, purpose)) {
            log.info("{} of trip already has a payment; event {} ignored", purpose, event.eventId());
            return Optional.empty();
        }
        Instant now = clock.instant();
        Payment payment = Payment.open(UuidV7.random(), tripId, purpose, id(p, fare ? "fareId" : "feeId"),
                id(p, "customerId"), p.hasNonNull("driverId") ? id(p, "driverId") : null, serviceType(p), amount,
                p.path("currency").asString(), now);
        PaymentAttempt attempt = payment.startAttempt(UuidV7.random(), provider.name(), now);
        payments.saveAndFlush(payment);
        attempts.save(attempt);
        return Optional.of(attempt.getId());
    }

    /**
     * A customer asks to pay a failed payment again (FR-PAY: retry after failure). Only a FAILED
     * payment gets a new attempt: one in flight or already paid is left alone, so double clicks
     * never create two charges.
     *
     * @return the new attempt to send, empty when there is nothing to retry
     */
    @Transactional
    public Optional<UUID> retry(UUID customerId, UUID paymentId, int maxAttempts) {
        Payment payment = payments.findByIdForUpdate(paymentId)
                .filter(p -> p.getCustomerId().equals(customerId))
                .orElseThrow(() -> ApiException.notFound("Payment"));
        if (payment.getStatus() != PaymentStatus.FAILED) {
            return Optional.empty();
        }
        if (payment.getAttemptCount() >= maxAttempts) {
            throw ApiException.rule("This payment was attempted " + maxAttempts
                    + " times; please contact support");
        }
        PaymentAttempt attempt = payment.startAttempt(UuidV7.random(), provider.name(), clock.instant());
        payments.saveAndFlush(payment);
        attempts.save(attempt);
        return Optional.of(attempt.getId());
    }

    /** What to send to the provider for an attempt whose outcome is not known yet. */
    @Transactional(readOnly = true)
    public Optional<PaymentProvider.ChargeRequest> request(UUID attemptId) {
        return attempts.findById(attemptId)
                .filter(PaymentAttempt::isPending)
                .map(attempt -> {
                    Payment payment = payments.findById(attempt.getPaymentId()).orElseThrow();
                    return new PaymentProvider.ChargeRequest(attempt.getIdempotencyKey(), payment.getCustomerId(),
                            payment.getAmount(), payment.getCurrency(),
                            payment.getPurpose() + " trip " + payment.getTripId());
                });
    }

    /**
     * Records the provider's answer, from the synchronous call or from a signed callback.
     * Idempotent: an attempt already resolved (by a concurrent worker, an earlier delivery or a
     * repeated callback) is left as it is, so events and ledger lines are written once (BR-015).
     *
     * @return whether this call settled the attempt ({@code false} if it was already resolved or
     *         the outcome is still pending at the provider)
     */
    @Transactional
    public boolean complete(UUID attemptId, PaymentProvider.ChargeResult result) {
        UUID paymentId = attempts.findPaymentId(attemptId).orElseThrow();
        Payment payment = payments.findByIdForUpdate(paymentId).orElseThrow();
        // Attempts only change under this lock, so this read is current.
        PaymentAttempt attempt = attempts.findById(attemptId).orElseThrow();
        if (!attempt.isPending()) {
            return false;
        }
        Instant now = clock.instant();
        switch (result.outcome()) {
            case PENDING -> {
                payment.awaitingCallback(attempt, result.reference(), now);
                payments.saveAndFlush(payment);
                return false;
            }
            case DECLINED -> {
                payment.failed(attempt, result.failureCode(), now);
                payments.saveAndFlush(payment);
                events.failed(payment, attempt);
                return true;
            }
            case SUCCEEDED -> {
                // handled below
            }
        }
        payment.succeeded(attempt, result.reference(), now);
        payments.saveAndFlush(payment);
        events.succeeded(payment, attempt);
        if (payment.getDriverId() != null) {
            creditDriver(payment, now);
        }
        return true;
    }

    /** Net earning and commission into the driver's ledger, once per payment (BR-011, BR-012). */
    private void creditDriver(Payment payment, Instant now) {
        CommissionRule rule = commissionRules.findEffective(payment.getServiceType(), payment.getSucceededAt())
                .orElseThrow(() -> new IllegalStateException("No commission rule for " + payment.getServiceType()));
        CommissionRule.Split split = rule.split(payment.getAmount());
        Wallet wallet = wallets.findForUpdate(payment.getDriverId(), payment.getCurrency())
                .orElseGet(() -> wallets.saveAndFlush(
                        Wallet.open(UuidV7.random(), payment.getDriverId(), payment.getCurrency(), now)));
        List<WalletEntry> lines = wallet.creditPayment(payment, split, UuidV7::random, now);
        entries.saveAll(lines);
        wallets.saveAndFlush(wallet);
        events.earningPosted(wallet, payment, split, now);
    }

    /** Pricing events published before serviceType was added carry none; both services share one rate today. */
    private static ServiceType serviceType(JsonNode p) {
        return p.hasNonNull("serviceType") ? ServiceType.valueOf(p.path("serviceType").asString()) : ServiceType.RIDE;
    }

    private static UUID id(JsonNode p, String field) {
        return UUID.fromString(p.path(field).asString());
    }
}
