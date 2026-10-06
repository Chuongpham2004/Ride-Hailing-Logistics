package com.rhl.trip.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The price the customer accepted, copied from the pricing-service quote (BR-007). The final
 * fare is settled by pricing-service after completion; this is what the trip was booked at.
 */
@Embeddable
public record FareSnapshot(
        @Column(name = "quote_id") UUID quoteId,
        @Column(name = "quoted_fare") Long quotedFare,
        @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "currency", length = 3) String currency,
        @Column(name = "surge_multiplier") BigDecimal surgeMultiplier,
        @Column(name = "pricing_rule_version") Integer pricingRuleVersion,
        @Column(name = "route_distance_meters") Integer distanceMeters,
        @Column(name = "route_duration_seconds") Integer durationSeconds) {

    /**
     * BR-006: a surge above 1.00 must have been shown and accepted at exactly the quoted
     * multiplier. A multiplier sent for an unsurged quote must match too, so a stale screen
     * is never booked silently.
     *
     * @param accepted the multiplier the customer confirmed, {@code null} if none was shown
     */
    public void requireSurgeConsent(BigDecimal accepted) {
        boolean surged = surgeMultiplier.compareTo(BigDecimal.ONE) > 0;
        if (accepted == null && surged) {
            throw DomainException.rule("This quote has a surge of x" + surgeMultiplier
                    + "; confirm it by sending acceptedSurgeMultiplier");
        }
        if (accepted != null && accepted.compareTo(surgeMultiplier) != 0) {
            throw DomainException.rule("The surge changed to x" + surgeMultiplier + "; get a new quote and confirm");
        }
    }
}
