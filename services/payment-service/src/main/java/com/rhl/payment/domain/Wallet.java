package com.rhl.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A driver's wallet (FR-WAL): one per driver and currency. The balance is the sum of the ledger
 * and is only changed by posting entries, under a row lock; it never goes negative (FR-WAL-009).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "wallets")
public class Wallet {

    public static final String PAYMENT = "PAYMENT";

    @Id
    private UUID id;

    @Column(name = "driver_id", nullable = false)
    private UUID driverId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private long balance;

    /** {@code null} until persisted, so Spring Data inserts instead of merging. */
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static Wallet open(UUID id, UUID driverId, String currency, Instant now) {
        Wallet wallet = new Wallet();
        wallet.id = Objects.requireNonNull(id);
        wallet.driverId = Objects.requireNonNull(driverId);
        wallet.currency = Objects.requireNonNull(currency);
        wallet.createdAt = now;
        wallet.updatedAt = now;
        return wallet;
    }

    /**
     * The driver's share of a paid trip (BR-012): the gross as an earning, then the platform
     * commission as a debit. A zero commission writes no line.
     */
    public List<WalletEntry> creditPayment(Payment payment, CommissionRule.Split split, Supplier<UUID> ids,
                                           Instant now) {
        if (!payment.getCurrency().equals(currency)) {
            throw new IllegalArgumentException("Wallet is in " + currency + ", payment in " + payment.getCurrency());
        }
        List<WalletEntry> entries = new ArrayList<>(2);
        entries.add(post(ids.get(), WalletEntry.EARNING, split.gross(), payment, split.ruleVersion(), now));
        if (split.commission() > 0) {
            entries.add(post(ids.get(), WalletEntry.COMMISSION, -split.commission(), payment, split.ruleVersion(),
                    now));
        }
        return entries;
    }

    private WalletEntry post(UUID entryId, String type, long amount, Payment payment, int ruleVersion, Instant now) {
        long after = Math.addExact(balance, amount);
        if (after < 0) {
            throw new IllegalStateException("Wallet balance cannot go negative");
        }
        balance = after;
        updatedAt = now;
        return WalletEntry.of(entryId, id, type, amount, after, PAYMENT, payment.getId(), payment.getTripId(),
                ruleVersion, now);
    }
}
