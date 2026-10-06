package com.rhl.trip.infrastructure.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** {@code idempotency_keys} (COM-008). */
@Repository
@RequiredArgsConstructor
public class IdempotencyKeyRepository {

    public record Entry(String requestHash, UUID resourceId) {
    }

    private final JdbcTemplate jdbc;

    /**
     * Claims the key inside the caller's transaction. A concurrent request with the same key
     * waits on the primary key until the first one commits or rolls back, so at most one of them
     * creates the resource.
     *
     * @return {@code true} when this transaction now owns the key
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claim(String scope, String key, String requestHash, UUID resourceId, Instant now) {
        return jdbc.update("""
                        INSERT INTO idempotency_keys (scope, idem_key, request_hash, resource_id, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (scope, idem_key) DO NOTHING
                        """,
                scope, key, requestHash, resourceId, Timestamp.from(now)) == 1;
    }

    public Optional<Entry> find(String scope, String key) {
        return jdbc.query("SELECT request_hash, resource_id FROM idempotency_keys WHERE scope = ? AND idem_key = ?",
                        (rs, i) -> new Entry(rs.getString("request_hash"), rs.getObject("resource_id", UUID.class)),
                        scope, key)
                .stream().findFirst();
    }
}
