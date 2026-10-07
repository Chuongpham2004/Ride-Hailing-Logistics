package com.rhl.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * One line of a driver's immutable ledger (BR-011). Created only through {@link Wallet}, which
 * keeps the balance in step; a database trigger refuses updates and deletes.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Immutable
@Table(name = "wallet_entries")
public class WalletEntry {

    public static final String EARNING = "EARNING";
    public static final String COMMISSION = "COMMISSION";
    public static final String ADJUSTMENT = "ADJUSTMENT";

    @Id
    private UUID id;

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Column(name = "entry_type", nullable = false)
    private String entryType;

    /** Signed: earnings credit, commissions debit, adjustments either way. */
    @Column(nullable = false)
    private long amount;

    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    @Column(name = "reference_type", nullable = false)
    private String referenceType;

    @Column(name = "reference_id", nullable = false)
    private UUID referenceId;

    @Column(name = "trip_id")
    private UUID tripId;

    @Column(name = "commission_rule_version")
    private Integer commissionRuleVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    static WalletEntry of(UUID id, UUID walletId, String type, long amount, long balanceAfter, String referenceType,
                          UUID referenceId, UUID tripId, Integer ruleVersion, Instant now) {
        WalletEntry entry = new WalletEntry();
        entry.id = id;
        entry.walletId = walletId;
        entry.entryType = type;
        entry.amount = amount;
        entry.balanceAfter = balanceAfter;
        entry.referenceType = referenceType;
        entry.referenceId = referenceId;
        entry.tripId = tripId;
        entry.commissionRuleVersion = ruleVersion;
        entry.createdAt = now;
        return entry;
    }
}
