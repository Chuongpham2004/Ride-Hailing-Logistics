package com.rhl.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What a customer owes for one trip and purpose (FR-PAY). The amount is the final fare or
 * cancellation fee pricing-service settled, never a client value. A failed charge never undoes
 * the trip: the payment stays owed and can be attempted again.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    private UUID id;

    @Column(name = "trip_id", nullable = false)
    private UUID tripId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentPurpose purpose;

    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "driver_id")
    private UUID driverId;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", nullable = false)
    private ServiceType serviceType;

    @Column(nullable = false)
    private long amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    private String provider;

    @Column(name = "provider_ref")
    private String providerRef;

    @Column(name = "failure_code")
    private String failureCode;

    /** Sum of succeeded refunds; never above {@link #amount} (BR-010, also a database check). */
    @Column(name = "refunded_amount", nullable = false)
    private long refundedAmount;

    /** {@code null} until persisted, so Spring Data inserts instead of merging. */
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "succeeded_at")
    private Instant succeededAt;

    public static Payment open(UUID id, UUID tripId, PaymentPurpose purpose, UUID sourceId, UUID customerId,
                               UUID driverId, ServiceType serviceType, long amount, String currency, Instant now) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Nothing to charge for " + purpose + " of trip " + tripId);
        }
        Payment payment = new Payment();
        payment.id = Objects.requireNonNull(id);
        payment.tripId = Objects.requireNonNull(tripId);
        payment.purpose = Objects.requireNonNull(purpose);
        payment.sourceId = Objects.requireNonNull(sourceId);
        payment.customerId = Objects.requireNonNull(customerId);
        payment.driverId = driverId;
        payment.serviceType = Objects.requireNonNull(serviceType);
        payment.amount = amount;
        payment.currency = Objects.requireNonNull(currency);
        payment.status = PaymentStatus.PENDING;
        payment.createdAt = now;
        payment.updatedAt = now;
        return payment;
    }

    /**
     * A new provider attempt for an unpaid payment. Callers make sure the previous attempt is no
     * longer in flight; its outcome must be known before another one is started.
     */
    public PaymentAttempt startAttempt(UUID attemptId, String providerName, Instant now) {
        if (status.isCaptured()) {
            throw new IllegalStateException("Payment " + id + " is already paid");
        }
        attemptCount++;
        status = PaymentStatus.PENDING;
        failureCode = null;
        updatedAt = now;
        return PaymentAttempt.start(attemptId, id, attemptCount, providerName, now);
    }

    public void succeeded(PaymentAttempt attempt, String reference, Instant now) {
        requireCurrent(attempt);
        attempt.succeed(reference, now);
        status = PaymentStatus.SUCCEEDED;
        provider = attempt.getProvider();
        providerRef = reference;
        succeededAt = now;
        updatedAt = now;
    }

    /** The provider accepted the charge and will report the outcome by callback. */
    public void awaitingCallback(PaymentAttempt attempt, String reference, Instant now) {
        requireCurrent(attempt);
        attempt.accepted(reference);
        updatedAt = now;
    }

    public void failed(PaymentAttempt attempt, String code, Instant now) {
        requireCurrent(attempt);
        attempt.fail(code, now);
        status = PaymentStatus.FAILED;
        provider = attempt.getProvider();
        failureCode = code;
        updatedAt = now;
    }

    /** What can still be refunded: the captured amount minus succeeded refunds. */
    public long refundable() {
        return status.isCaptured() ? amount - refundedAmount : 0;
    }

    /**
     * Opens a refund of {@code refundAmount} (FR-PAY, BR-010). One refund at a time: the next one
     * is accepted only once the provider has settled this one, so the refundable amount is exact.
     *
     * @throws IllegalStateException    when the payment was never captured or a refund is in flight
     * @throws IllegalArgumentException when the amount is not positive or above {@link #refundable()}
     */
    public Refund startRefund(UUID refundId, long refundAmount, RefundReason reason, String note, String providerName,
                              String requestKey, String requestHash, UUID requestedBy, Instant now) {
        if (status != PaymentStatus.SUCCEEDED && status != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new IllegalStateException("Payment " + id + " cannot be refunded while " + status);
        }
        if (refundAmount <= 0 || refundAmount > refundable()) {
            throw new IllegalArgumentException("Refund amount must be between 1 and " + refundable());
        }
        status = PaymentStatus.REFUND_PENDING;
        updatedAt = now;
        return Refund.open(refundId, this, refundAmount, reason, note, providerName, requestKey, requestHash,
                requestedBy, now);
    }

    public void refunded(Refund refund, String reference, Instant now) {
        requireInFlight(refund);
        refund.succeed(reference, now);
        refundedAmount = Math.addExact(refundedAmount, refund.getAmount());
        status = refundedAmount == amount ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
        updatedAt = now;
    }

    /** The provider refused the refund: the payment is back to what it was before it. */
    public void refundFailed(Refund refund, String code, Instant now) {
        requireInFlight(refund);
        refund.fail(code, now);
        status = refundedAmount == 0 ? PaymentStatus.SUCCEEDED : PaymentStatus.PARTIALLY_REFUNDED;
        updatedAt = now;
    }

    private void requireInFlight(Refund refund) {
        if (!refund.getPaymentId().equals(id) || status != PaymentStatus.REFUND_PENDING) {
            throw new IllegalStateException("Refund " + refund.getId() + " is not in flight for payment " + id);
        }
    }

    private void requireCurrent(PaymentAttempt attempt) {
        if (!attempt.getPaymentId().equals(id) || attempt.getAttemptNo() != attemptCount) {
            throw new IllegalStateException("Attempt " + attempt.getAttemptNo() + " is not the current one");
        }
    }
}
