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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Versioned surge formula for one service type (FR-PRI-007…012):
 * {@code 1 + slope × (demand / max(supply, 1) − ratioThreshold)}, clamped to
 * [1, maxMultiplier] and rounded down to a multiple of {@code step}. Below {@code minDemand}
 * requests there is no surge at all. Never lowers a price, never exceeds the cap.
 *
 * <p>A version is never edited: administrators schedule the next one, which closes this one when
 * it starts, so every quote can name the formula it was priced with.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
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

    /** {@code null} for versions created by migration. */
    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Optimistic lock; {@code null} until persisted so Spring Data inserts instead of merging. */
    @Version
    @Column(name = "row_version")
    private Long rowVersion;

    /**
     * The numbers of a surge formula. Bounds follow the columns (two decimals) and keep a typo
     * from producing an absurd price.
     */
    public record Formula(int minDemand, BigDecimal ratioThreshold, BigDecimal slope, BigDecimal maxMultiplier,
                          BigDecimal step) {

        public static final BigDecimal HIGHEST_CAP = new BigDecimal("5.00");

        public Formula {
            Objects.requireNonNull(ratioThreshold);
            Objects.requireNonNull(slope);
            Objects.requireNonNull(maxMultiplier);
            Objects.requireNonNull(step);
            if (minDemand < 1 || minDemand > 10_000) {
                throw DomainException.rule("minDemand must be between 1 and 10000");
            }
            requireRange("ratioThreshold", ratioThreshold, BigDecimal.ZERO, new BigDecimal("100.00"));
            requireRange("slope", slope, BigDecimal.ZERO, new BigDecimal("10.00"));
            requireRange("maxMultiplier", maxMultiplier, BigDecimal.ONE, HIGHEST_CAP);
            requireRange("step", step, new BigDecimal("0.01"), BigDecimal.ONE);
            ratioThreshold = ratioThreshold.setScale(2);
            slope = slope.setScale(2);
            maxMultiplier = maxMultiplier.setScale(2);
            step = step.setScale(2);
        }

        private static void requireRange(String name, BigDecimal value, BigDecimal min, BigDecimal max) {
            if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
                throw DomainException.rule(name + " must be between " + min + " and " + max);
            }
            if (value.stripTrailingZeros().scale() > 2) {
                throw DomainException.rule(name + " has at most two decimals");
            }
        }
    }

    /** For tests; production versions come from migration or {@link #supersede}. */
    public static SurgeRule of(ServiceType serviceType, int version, int minDemand, String ratioThreshold,
                               String slope, String maxMultiplier, String step) {
        return create(UUID.randomUUID(), serviceType, version, new Formula(minDemand, new BigDecimal(ratioThreshold),
                new BigDecimal(slope), new BigDecimal(maxMultiplier), new BigDecimal(step)), Instant.EPOCH, null,
                Instant.EPOCH);
    }

    private static SurgeRule create(UUID id, ServiceType serviceType, int version, Formula formula,
                                    Instant effectiveFrom, UUID createdBy, Instant now) {
        SurgeRule rule = new SurgeRule();
        rule.id = Objects.requireNonNull(id);
        rule.serviceType = Objects.requireNonNull(serviceType);
        rule.version = version;
        rule.minDemand = formula.minDemand();
        rule.ratioThreshold = formula.ratioThreshold();
        rule.slope = formula.slope();
        rule.maxMultiplier = formula.maxMultiplier();
        rule.step = formula.step();
        rule.effectiveFrom = Objects.requireNonNull(effectiveFrom);
        rule.createdBy = createdBy;
        rule.createdAt = now;
        return rule;
    }

    /**
     * The next version of this rule, taking over at {@code effectiveFrom}; closes this one then.
     * Quotes issued before keep the multiplier they were given.
     *
     * @throws DomainException when the new version would not start in the future, after this one
     */
    public SurgeRule supersede(UUID newId, Formula formula, Instant effectiveFrom, UUID createdBy, Instant now) {
        effectiveFrom = effectiveFrom.truncatedTo(ChronoUnit.MICROS); // stored precision
        if (!effectiveFrom.isAfter(now)) {
            throw DomainException.rule("A new surge rule must take effect in the future, not retroactively");
        }
        if (!effectiveFrom.isAfter(this.effectiveFrom)) {
            throw DomainException.rule("A new surge rule must start after the current one (" + this.effectiveFrom
                    + ")");
        }
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw DomainException.rule("The current surge rule ends at " + effectiveTo + ", leaving a gap");
        }
        SurgeRule next = create(newId, serviceType, version + 1, formula, effectiveFrom, createdBy, now);
        this.effectiveTo = effectiveFrom;
        return next;
    }

    public Formula formula() {
        return new Formula(minDemand, ratioThreshold, slope, maxMultiplier, step);
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
