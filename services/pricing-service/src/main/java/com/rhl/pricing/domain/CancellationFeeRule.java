package com.rhl.pricing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Versioned cancellation fee policy for one service type (FR-CAN, TBD-07). Only the customer
 * pays, and only once a driver was committed to the trip:
 * <ul>
 *   <li>cancelled before a driver accepted: free ({@code NOT_ASSIGNED});</li>
 *   <li>by the customer within the free window after acceptance: free;</li>
 *   <li>by the customer after it: {@code customerCancelFee} ({@code LATE_CANCELLATION});</li>
 *   <li>by the driver at the pickup because the customer did not show up: {@code noShowFee};</li>
 *   <li>by the driver for any other reason, or by staff: free ({@code NOT_CHARGEABLE}).</li>
 * </ul>
 * A fee never exceeds the price the trip was booked at.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Immutable
@Table(name = "cancellation_fee_rules")
public class CancellationFeeRule {

    public static final String NOT_ASSIGNED = "NOT_ASSIGNED";
    public static final String WITHIN_FREE_WINDOW = "WITHIN_FREE_WINDOW";
    public static final String LATE_CANCELLATION = "LATE_CANCELLATION";
    public static final String NO_SHOW = "NO_SHOW";
    public static final String NOT_CHARGEABLE = "NOT_CHARGEABLE";

    /** Trip states in which a driver is committed (trip-service's ux_trips_active_driver). */
    private static final Set<String> ASSIGNED = Set.of("ACCEPTED", "PICKING_UP", "ARRIVED", "IN_TRIP");

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", nullable = false)
    private ServiceType serviceType;

    @Column(nullable = false)
    private int version;

    @Column(name = "free_window_seconds", nullable = false)
    private int freeWindowSeconds;

    @Column(name = "customer_cancel_fee", nullable = false)
    private long customerCancelFee;

    @Column(name = "no_show_fee", nullable = false)
    private long noShowFee;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    /** What a cancellation looked like, as reported by trip-service's TripCancelled. */
    public record Cancellation(String actorType, String oldStatus, String reason, Instant acceptedAt,
                               Instant cancelledAt, Long bookedFare) {
    }

    public record Decision(String decision, long fee) {
    }

    /** For tests and future administration; rules are otherwise created by migration. */
    public static CancellationFeeRule of(ServiceType serviceType, int version, int freeWindowSeconds,
                                         long customerCancelFee, long noShowFee) {
        CancellationFeeRule rule = new CancellationFeeRule();
        rule.id = UUID.randomUUID();
        rule.serviceType = serviceType;
        rule.version = version;
        rule.freeWindowSeconds = freeWindowSeconds;
        rule.customerCancelFee = customerCancelFee;
        rule.noShowFee = noShowFee;
        rule.currency = "VND";
        rule.effectiveFrom = Instant.EPOCH;
        return rule;
    }

    public Decision decide(Cancellation c) {
        if (!ASSIGNED.contains(c.oldStatus())) {
            return new Decision(NOT_ASSIGNED, 0);
        }
        if ("CUSTOMER".equals(c.actorType())) {
            // Without an acceptance time (trips booked before it was published) nobody is charged.
            boolean withinWindow = c.acceptedAt() == null
                    || !Duration.between(c.acceptedAt(), c.cancelledAt()).minusSeconds(freeWindowSeconds).isPositive();
            return withinWindow
                    ? new Decision(WITHIN_FREE_WINDOW, 0)
                    : new Decision(LATE_CANCELLATION, capped(customerCancelFee, c));
        }
        if ("DRIVER".equals(c.actorType()) && "ARRIVED".equals(c.oldStatus())
                && "CUSTOMER_NO_SHOW".equals(c.reason())) {
            return new Decision(NO_SHOW, capped(noShowFee, c));
        }
        return new Decision(NOT_CHARGEABLE, 0);
    }

    private static long capped(long fee, Cancellation c) {
        return c.bookedFare() == null ? fee : Math.min(fee, c.bookedFare());
    }
}
