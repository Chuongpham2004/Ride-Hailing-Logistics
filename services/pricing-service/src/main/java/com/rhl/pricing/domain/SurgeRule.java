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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * Versioned surge formula for one service type (FR-PRI-007…012):
 * {@code 1 + slope × (demand / max(supply, 1) − ratioThreshold)}, clamped to
 * [1, maxMultiplier] and rounded down to a multiple of {@code step}. Below {@code minDemand}
 * requests there is no surge at all. Never lowers a price, never exceeds the cap.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Immutable
@Table(name = "surge_rules")
public class SurgeRule {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", nullable = false)
    private ServiceType serviceType;

    @Column(nullable = false)
    private int version;

    @Column(name = "min_demand", nullable = false)
    private int minDemand;

    @Column(name = "ratio_threshold", nullable = false)
    private BigDecimal ratioThreshold;

    @Column(nullable = false)
    private BigDecimal slope;

    @Column(name = "max_multiplier", nullable = false)
    private BigDecimal maxMultiplier;

    @Column(nullable = false)
    private BigDecimal step;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    /** For tests and future administration; rules are otherwise created by migration. */
    public static SurgeRule of(ServiceType serviceType, int version, int minDemand, String ratioThreshold,
                               String slope, String maxMultiplier, String step) {
        SurgeRule rule = new SurgeRule();
        rule.id = UUID.randomUUID();
        rule.serviceType = serviceType;
        rule.version = version;
        rule.minDemand = minDemand;
        rule.ratioThreshold = new BigDecimal(ratioThreshold);
        rule.slope = new BigDecimal(slope);
        rule.maxMultiplier = new BigDecimal(maxMultiplier);
        rule.step = new BigDecimal(step);
        rule.effectiveFrom = Instant.EPOCH;
        return rule;
    }

    /** Deterministic: the same counts and rule version always give the same multiplier. */
    public BigDecimal multiplier(int demand, int supply) {
        if (demand < 0 || supply < 0) {
            throw new IllegalArgumentException("Counts cannot be negative");
        }
        if (demand < minDemand) {
            return BigDecimal.ONE.setScale(2);
        }
        BigDecimal ratio = BigDecimal.valueOf(demand).divide(BigDecimal.valueOf(Math.max(supply, 1)), 4,
                RoundingMode.HALF_UP);
        BigDecimal raw = BigDecimal.ONE.add(slope.multiply(ratio.subtract(ratioThreshold)));
        BigDecimal clamped = raw.max(BigDecimal.ONE).min(maxMultiplier);
        BigDecimal steps = clamped.divide(step, 0, RoundingMode.FLOOR);
        return steps.multiply(step).max(BigDecimal.ONE).setScale(2, RoundingMode.UNNECESSARY);
    }
}
