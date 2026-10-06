package com.rhl.pricing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** The fee decision for one cancelled trip, with the rule version it came from (FR-CAN). */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Immutable
@Table(name = "cancellation_fees")
public class CancellationFee {

    @Id
    private UUID id;

    @Column(name = "trip_id", nullable = false)
    private UUID tripId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "driver_id")
    private UUID driverId;

    @Column(name = "quote_id")
    private UUID quoteId;

    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;

    @Column(name = "rule_version", nullable = false)
    private int ruleVersion;

    @Column(name = "actor_type", nullable = false)
    private String actorType;

    @Column(name = "old_status", nullable = false)
    private String oldStatus;

    @Column(name = "cancel_reason", nullable = false)
    private String cancelReason;

    @Column(nullable = false)
    private String decision;

    @Column(nullable = false)
    private long fee;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "cancelled_at", nullable = false)
    private Instant cancelledAt;

    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt;

    public static CancellationFee decide(UUID id, UUID tripId, UUID customerId, UUID driverId, UUID quoteId,
                                         CancellationFeeRule rule, CancellationFeeRule.Cancellation cancellation,
                                         Instant now) {
        CancellationFeeRule.Decision decision = rule.decide(cancellation);
        CancellationFee fee = new CancellationFee();
        fee.id = Objects.requireNonNull(id);
        fee.tripId = Objects.requireNonNull(tripId);
        fee.customerId = Objects.requireNonNull(customerId);
        fee.driverId = driverId;
        fee.quoteId = quoteId;
        fee.ruleId = rule.getId();
        fee.ruleVersion = rule.getVersion();
        fee.actorType = cancellation.actorType();
        fee.oldStatus = cancellation.oldStatus();
        fee.cancelReason = cancellation.reason();
        fee.decision = decision.decision();
        fee.fee = decision.fee();
        fee.currency = rule.getCurrency();
        fee.cancelledAt = cancellation.cancelledAt();
        fee.calculatedAt = now;
        return fee;
    }
}
