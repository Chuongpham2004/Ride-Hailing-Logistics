package com.rhl.pricing.domain;

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
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Prices for one service type in one region during [effectiveFrom, effectiveTo) (FR-PRI).
 * Prices never change after creation: a new version is created and the current one is closed
 * at the moment the new one takes over, so every quote can name the exact rule it used.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "pricing_rules")
public class PricingRule {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", nullable = false)
    private ServiceType serviceType;

    @Column(name = "region_code", nullable = false)
    private String regionCode;

    @Column(nullable = false)
    private int version;

    @Column(name = "base_fare", nullable = false)
    private long baseFare;

    @Column(name = "per_km", nullable = false)
    private long perKm;

    @Column(name = "per_minute", nullable = false)
    private long perMinute;

    @Column(name = "minimum_fare", nullable = false)
    private long minimumFare;

    /** ISO 4217, stored as CHAR(3). */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Optimistic lock; {@code null} until persisted so Spring Data inserts instead of merging. */
    @Version
    @Column(name = "row_version")
    private Long rowVersion;

    /**
     * The next version of this rule, taking over at {@code effectiveFrom}; closes this one then.
     *
     * @throws DomainException when the new rule would start before this one or not in the future
     */
    public PricingRule supersede(UUID newId, Tariff tariff, Instant effectiveFrom, UUID createdBy, Instant now) {
        effectiveFrom = effectiveFrom.truncatedTo(ChronoUnit.MICROS); // stored precision
        if (!effectiveFrom.isAfter(now)) {
            throw DomainException.rule("A new rule must take effect in the future, not retroactively");
        }
        if (!effectiveFrom.isAfter(this.effectiveFrom)) {
            throw DomainException.rule("A new rule must start after the current one (" + this.effectiveFrom + ")");
        }
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw DomainException.rule("The current rule ends at " + effectiveTo + ", leaving a gap");
        }
        PricingRule next = create(newId, serviceType, regionCode, version + 1, tariff, currency, effectiveFrom,
                createdBy, now);
        this.effectiveTo = effectiveFrom;
        return next;
    }

    public static PricingRule create(UUID id, ServiceType serviceType, String regionCode, int version, Tariff tariff,
                                     String currency, Instant effectiveFrom, UUID createdBy, Instant now) {
        PricingRule rule = new PricingRule();
        rule.id = Objects.requireNonNull(id);
        rule.serviceType = Objects.requireNonNull(serviceType);
        rule.regionCode = Objects.requireNonNull(regionCode);
        rule.version = version;
        rule.baseFare = tariff.baseFare();
        rule.perKm = tariff.perKm();
        rule.perMinute = tariff.perMinute();
        rule.minimumFare = tariff.minimumFare();
        rule.currency = Objects.requireNonNull(currency);
        rule.effectiveFrom = Objects.requireNonNull(effectiveFrom);
        rule.createdBy = createdBy;
        rule.createdAt = now;
        return rule;
    }

    public Tariff tariff() {
        return new Tariff(baseFare, perKm, perMinute, minimumFare);
    }

    public boolean isEffectiveAt(Instant at) {
        return !at.isBefore(effectiveFrom) && (effectiveTo == null || at.isBefore(effectiveTo));
    }
}
