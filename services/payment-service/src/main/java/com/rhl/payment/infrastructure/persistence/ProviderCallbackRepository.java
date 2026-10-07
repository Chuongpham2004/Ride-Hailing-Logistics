package com.rhl.payment.infrastructure.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** {@code provider_callbacks}: one row per provider event ID, whatever happened to it. */
@Repository
@RequiredArgsConstructor
public class ProviderCallbackRepository {

    private final JdbcTemplate jdbc;

    /**
     * @return {@code false} when this provider event was already received (redelivery or replay);
     *         a concurrent duplicate waits on the unique key until the first one commits
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean receive(UUID id, String provider, String eventId, String eventType, String idempotencyKey,
                           String payload, Instant signedAt, Instant now) {
        return jdbc.update("""
                        INSERT INTO provider_callbacks (id, provider, provider_event_id, event_type, idempotency_key,
                                                        outcome, payload, signed_at, received_at)
                        VALUES (?, ?, ?, ?, ?, 'RECEIVED', CAST(? AS JSONB), ?, ?)
                        ON CONFLICT (provider, provider_event_id) DO NOTHING
                        """,
                id, provider, eventId, eventType, idempotencyKey, payload, Timestamp.from(signedAt),
                Timestamp.from(now)) == 1;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void resolve(UUID id, String outcome, UUID paymentId, UUID refundId, String reason) {
        jdbc.update("UPDATE provider_callbacks SET outcome = ?, payment_id = ?, refund_id = ?, reason = ? WHERE id = ?",
                outcome, paymentId, refundId, reason, id);
    }
}
