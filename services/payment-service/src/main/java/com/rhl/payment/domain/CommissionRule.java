package com.rhl.payment.domain;

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
 * Platform commission for one service type, versioned (FR-WAL, TBD-10). Net earning =
 * gross − commission (BR-012); the commission is rounded half-up to whole VND.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Immutable
@Table(name = "commission_rules")
public class CommissionRule {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", nullable = false)
    private ServiceType serviceType;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private BigDecimal rate;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    /** For tests and future administration; rules are otherwise created by migration. */
    public static CommissionRule of(ServiceType serviceType, int version, String rate) {
        CommissionRule rule = new CommissionRule();
        rule.id = UUID.randomUUID();
        rule.serviceType = serviceType;
        rule.version = version;
        rule.rate = new BigDecimal(rate);
        rule.effectiveFrom = Instant.EPOCH;
        return rule;
    }

    public record Split(long gross, long commission, long net, int ruleVersion) {
    }

    public Split split(long gross) {
        if (gross < 0) {
            throw new IllegalArgumentException("Gross amount cannot be negative");
        }
        long commission = BigDecimal.valueOf(gross).multiply(rate).setScale(0, RoundingMode.HALF_UP).longValueExact();
        return new Split(gross, commission, gross - commission, version);
    }
}
