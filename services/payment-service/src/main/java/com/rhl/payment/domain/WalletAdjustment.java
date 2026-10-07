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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * A finance correction to a driver's wallet (BR-011, BR-012): why, by whom and linked to what.
 * The money moves through the {@code ADJUSTMENT} ledger line that references it. Append-only.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Immutable
@Table(name = "wallet_adjustments")
public class WalletAdjustment {

    @Id
    private UUID id;

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    /** Signed: positive credits the driver, negative debits. */
    @Column(nullable = false)
    private long amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AdjustmentReason reason;

    private String note;

    @Column(name = "trip_id")
    private UUID tripId;

    @Column(name = "refund_id")
    private UUID refundId;

    @Column(name = "request_key", nullable = false)
    private String requestKey;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    static WalletAdjustment of(UUID id, Wallet wallet, long amount, AdjustmentReason reason, String note, UUID tripId,
                               UUID refundId, String requestKey, String requestHash, UUID requestedBy, Instant now) {
        WalletAdjustment adjustment = new WalletAdjustment();
        adjustment.id = id;
        adjustment.walletId = wallet.getId();
        adjustment.amount = amount;
        adjustment.currency = wallet.getCurrency();
        adjustment.reason = reason;
        adjustment.note = note;
        adjustment.tripId = tripId;
        adjustment.refundId = refundId;
        adjustment.requestKey = requestKey;
        adjustment.requestHash = requestHash;
        adjustment.requestedBy = requestedBy;
        adjustment.createdAt = now;
        return adjustment;
    }
}
