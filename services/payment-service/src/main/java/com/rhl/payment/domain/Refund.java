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
 * Money given back to a customer for a captured payment (FR-PAY): full or partial, with a reason
 * and a link to the original charge. Created only through {@link Payment#startRefund}, which
 * keeps the refunded total within the captured amount (BR-010). Sent to the provider once, with
 * its own idempotency key; an unknown outcome is asked again with the same key.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "refunds")
public class Refund {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "trip_id", nullable = false)
    private UUID tripId;

    @Column(nullable = false)
    private long amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RefundReason reason;

    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RefundStatus status;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(nullable = false)
    private String provider;

    @Column(name = "provider_ref")
    private String providerRef;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "request_key", nullable = false)
    private String requestKey;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** {@code null} until persisted, so Spring Data inserts instead of merging. */
    @Version
    private Long version;

    static Refund open(UUID id, Payment payment, long amount, RefundReason reason, String note, String provider,
                       String requestKey, String requestHash, UUID requestedBy, Instant now) {
        Refund refund = new Refund();
        refund.id = Objects.requireNonNull(id);
        refund.paymentId = payment.getId();
        refund.tripId = payment.getTripId();
        refund.amount = amount;
        refund.currency = payment.getCurrency();
        refund.reason = Objects.requireNonNull(reason);
        refund.note = note;
        refund.status = RefundStatus.PENDING;
        refund.idempotencyKey = "refund:" + id;
        refund.provider = Objects.requireNonNull(provider);
        refund.requestKey = Objects.requireNonNull(requestKey);
        refund.requestHash = Objects.requireNonNull(requestHash);
        refund.requestedBy = Objects.requireNonNull(requestedBy);
        refund.createdAt = now;
        return refund;
    }

    public boolean isPending() {
        return status == RefundStatus.PENDING;
    }

    /** Still pending: the provider took the refund and will call back with the outcome. */
    public void accepted(String reference) {
        requirePending();
        providerRef = reference;
    }

    void succeed(String reference, Instant now) {
        requirePending();
        status = RefundStatus.SUCCEEDED;
        providerRef = Objects.requireNonNull(reference);
        completedAt = now;
    }

    void fail(String code, Instant now) {
        requirePending();
        status = RefundStatus.FAILED;
        failureCode = code;
        completedAt = now;
    }

    private void requirePending() {
        if (status != RefundStatus.PENDING) {
            throw new IllegalStateException("Refund " + id + " is already " + status);
        }
    }
}
