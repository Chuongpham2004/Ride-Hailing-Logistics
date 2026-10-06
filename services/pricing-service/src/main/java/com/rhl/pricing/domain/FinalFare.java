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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The fare a completed trip is charged, settled once per trip (BR-009). {@code UPFRONT}: exactly
 * the booked quote, so the surge is the one the customer accepted and nothing else (README §7,
 * Pricing: "Cước cuối không tự áp surge khác hệ số khách đã xác nhận").
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Immutable
@Table(name = "final_fares")
public class FinalFare {

    public static final String UPFRONT = "UPFRONT";

    @Id
    private UUID id;

    @Column(name = "trip_id", nullable = false)
    private UUID tripId;

    @Column(name = "quote_id", nullable = false)
    private UUID quoteId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "driver_id", nullable = false)
    private UUID driverId;

    @Column(nullable = false)
    private String method;

    @Column(name = "base_fare", nullable = false)
    private long baseFare;

    @Column(name = "distance_fare", nullable = false)
    private long distanceFare;

    @Column(name = "time_fare", nullable = false)
    private long timeFare;

    @Column(name = "minimum_fare_adjustment", nullable = false)
    private long minimumFareAdjustment;

    @Column(name = "surge_amount", nullable = false)
    private long surgeAmount;

    @Column(name = "rounding_adjustment", nullable = false)
    private long roundingAdjustment;

    @Column(nullable = false)
    private long total;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "surge_multiplier", nullable = false)
    private BigDecimal surgeMultiplier;

    @Column(name = "pricing_rule_version", nullable = false)
    private int pricingRuleVersion;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    @Column(name = "finalized_at", nullable = false)
    private Instant finalizedAt;

    /** @throws IllegalArgumentException when the quote is not this customer's (never priced for them) */
    public static FinalFare upfront(UUID id, UUID tripId, UUID customerId, UUID driverId, FareQuote quote,
                                    Instant completedAt, Instant now) {
        if (!quote.getCustomerId().equals(customerId)) {
            throw new IllegalArgumentException("Quote " + quote.getId() + " was not issued to the trip's customer");
        }
        FinalFare fare = new FinalFare();
        fare.id = Objects.requireNonNull(id);
        fare.tripId = Objects.requireNonNull(tripId);
        fare.quoteId = quote.getId();
        fare.customerId = customerId;
        fare.driverId = Objects.requireNonNull(driverId);
        fare.method = UPFRONT;
        FareBreakdown b = quote.breakdown();
        fare.baseFare = b.baseFare();
        fare.distanceFare = b.distanceFare();
        fare.timeFare = b.timeFare();
        fare.minimumFareAdjustment = b.minimumFareAdjustment();
        fare.surgeAmount = b.surgeAmount();
        fare.roundingAdjustment = b.roundingAdjustment();
        fare.total = b.total();
        fare.currency = quote.getCurrency();
        fare.surgeMultiplier = quote.getSurgeMultiplier();
        fare.pricingRuleVersion = quote.getRuleVersion();
        fare.completedAt = completedAt;
        fare.finalizedAt = now;
        return fare;
    }

    public FareBreakdown breakdown() {
        return new FareBreakdown(baseFare, distanceFare, timeFare, minimumFareAdjustment, surgeAmount,
                roundingAdjustment, total, surgeMultiplier);
    }
}
