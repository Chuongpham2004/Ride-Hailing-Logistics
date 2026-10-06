package com.rhl.common.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Idempotent consumer guard (FR-EVT-004). Call {@link #markProcessed} first inside the same
 * transaction as the business change; if it returns {@code false} the event was already applied
 * and the consumer must skip it.
 *
 * <pre>
 * CREATE TABLE processed_events (
 *   consumer     VARCHAR(100) NOT NULL,
 *   event_id     UUID         NOT NULL,
 *   processed_at TIMESTAMPTZ  NOT NULL,
 *   PRIMARY KEY (consumer, event_id)
 * );
 * </pre>
 */
public class ProcessedEvents {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ProcessedEvents(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(String consumer, UUID eventId) {
        return jdbc.update("""
                        INSERT INTO processed_events (consumer, event_id, processed_at) VALUES (?, ?, ?)
                        ON CONFLICT (consumer, event_id) DO NOTHING
                        """,
                consumer, eventId, java.sql.Timestamp.from(clock.instant())) == 1;
    }
}
