package com.rhl.pricing.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A price offered to one customer for one route, valid until {@code expiresAt} (FR-PRI, BR-005).
 * Immutable: it is the snapshot a trip is created from (BR-007).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Immutable
@Table(name = "fare_quotes")
public class FareQuote {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", nullable = false)
    private ServiceType serviceType;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "latitude", column = @Column(name = "pickup_latitude")),
            @AttributeOverride(name = "longitude", column = @Column(name = "pickup_longitude")),
            @AttributeOverride(name = "address", column = @Column(name = "pickup_address"))})
    private Stop pickup;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "latitude", column = @Column(name = "dropoff_latitude")),
            @AttributeOverride(name = "longitude", column = @Column(name = "dropoff_longitude")),
            @AttributeOverride(name = "address", column = @Column(name = "dropoff_address"))})
    private Stop dropoff;

    @Column(name = "distance_meters", nullable = false)
    private int distanceMeters;

    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;

    @Column(name = "route_source", nullable = false)
    private String routeSource;

    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;

    @Column(name = "rule_version", nullable = false)
    private int ruleVersion;

    @Column(name = "surge_multiplier", nullable = false)
    private BigDecimal surgeMultiplier;

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

    /** ISO 4217, stored as CHAR(3). */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public static FareQuote issue(UUID id, UUID customerId, ServiceType serviceType, Stop pickup, Stop dropoff,
                                  RouteEstimate route, PricingRule rule, FareBreakdown fare, Duration ttl,
                                  Instant now) {
        if (rule.getServiceType() != serviceType || !rule.isEffectiveAt(now)) {
            throw new IllegalArgumentException("Rule " + rule.getId() + " does not apply to this quote");
        }
        FareQuote quote = new FareQuote();
        quote.id = Objects.requireNonNull(id);
        quote.customerId = Objects.requireNonNull(customerId);
        quote.serviceType = serviceType;
        quote.pickup = pickup;
        quote.dropoff = dropoff;
        quote.distanceMeters = route.distanceMeters();
        quote.durationSeconds = route.durationSeconds();
        quote.routeSource = route.source();
        quote.ruleId = rule.getId();
        quote.ruleVersion = rule.getVersion();
        quote.surgeMultiplier = fare.surgeMultiplier();
        quote.baseFare = fare.baseFare();
        quote.distanceFare = fare.distanceFare();
        quote.timeFare = fare.timeFare();
        quote.minimumFareAdjustment = fare.minimumFareAdjustment();
        quote.surgeAmount = fare.surgeAmount();
        quote.roundingAdjustment = fare.roundingAdjustment();
        quote.total = fare.total();
        quote.currency = rule.getCurrency();
        quote.createdAt = now;
        quote.expiresAt = now.plus(ttl);
        return quote;
    }

    public FareBreakdown breakdown() {
        return new FareBreakdown(baseFare, distanceFare, timeFare, minimumFareAdjustment, surgeAmount,
                roundingAdjustment, total, surgeMultiplier);
    }
}
