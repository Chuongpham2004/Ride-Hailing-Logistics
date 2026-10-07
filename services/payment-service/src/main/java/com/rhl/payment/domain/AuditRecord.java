package com.rhl.payment.domain;

import com.rhl.common.id.UuidV7;
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

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only audit trail for refunds and wallet corrections (BR-014, FR-ADM-006). {@code delta}
 * holds only amounts, reasons and references: never card data or free-text notes.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Immutable
@Table(name = "audit_records")
public class AuditRecord {

    @Id
    private UUID id;

    /** {@code null} for the system, e.g. a provider callback settling a refund. */
    @Column(name = "actor_id")
    private UUID actorId;

    @Column(nullable = false)
    private String action;

    @Column(name = "target_type", nullable = false)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private String targetId;

    @Column(nullable = false)
    private String result;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> delta;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public static AuditRecord of(UUID actorId, String action, String targetType, String targetId, String result,
                                 Map<String, Object> delta, String correlationId, Instant now) {
        AuditRecord r = new AuditRecord();
        r.id = UuidV7.random();
        r.actorId = actorId;
        r.action = action;
        r.targetType = targetType;
        r.targetId = targetId;
        r.result = result;
        r.delta = delta == null || delta.isEmpty() ? null : Map.copyOf(delta);
        r.correlationId = correlationId;
        r.occurredAt = now;
        return r;
    }
}
