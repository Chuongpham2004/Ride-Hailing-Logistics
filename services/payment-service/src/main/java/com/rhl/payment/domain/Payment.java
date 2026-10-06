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
        if (status == PaymentStatus.SUCCEEDED) {
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

    private void requireCurrent(PaymentAttempt attempt) {
        if (!attempt.getPaymentId().equals(id) || attempt.getAttemptNo() != attemptCount) {
            throw new IllegalStateException("Attempt " + attempt.getAttemptNo() + " is not the current one");
        }
    }
}
