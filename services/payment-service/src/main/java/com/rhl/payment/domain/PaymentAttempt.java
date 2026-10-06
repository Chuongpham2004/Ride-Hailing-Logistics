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

import java.time.Instant;
import java.util.UUID;

/**
 * One call to the payment provider. The idempotency key is sent with the call, so re-sending an
 * attempt whose outcome was lost (timeout, crash) can never charge twice.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "payment_attempts")
public class PaymentAttempt {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(nullable = false)
    private String provider;

    @Column(name = "provider_ref")
    private String providerRef;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** {@code null} until persisted, so Spring Data inserts instead of merging. */
    @Version
    private Long version;

    static PaymentAttempt start(UUID id, UUID paymentId, int attemptNo, String provider, Instant now) {
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.id = id;
        attempt.paymentId = paymentId;
        attempt.attemptNo = attemptNo;
        attempt.idempotencyKey = paymentId + ":" + attemptNo;
        attempt.status = PaymentStatus.PENDING;
        attempt.provider = provider;
        attempt.createdAt = now;
        return attempt;
    }

    public boolean isPending() {
        return status == PaymentStatus.PENDING;
    }

    /** Still pending: the provider took the charge and will call back with the outcome. */
    void accepted(String reference) {
        requirePending();
        providerRef = reference;
    }

    public boolean isAwaitingCallback() {
        return status == PaymentStatus.PENDING && providerRef != null;
    }

    void succeed(String reference, Instant now) {
        requirePending();
        status = PaymentStatus.SUCCEEDED;
        providerRef = reference;
        completedAt = now;
    }

    void fail(String code, Instant now) {
        requirePending();
        status = PaymentStatus.FAILED;
        failureCode = code;
        completedAt = now;
    }

    private void requirePending() {
        if (status != PaymentStatus.PENDING) {
            throw new IllegalStateException("Attempt " + attemptNo + " is already " + status);
        }
    }
}
