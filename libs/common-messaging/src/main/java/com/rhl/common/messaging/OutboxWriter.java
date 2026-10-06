package com.rhl.common.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rhl.common.id.UuidV7;
import com.rhl.common.web.CorrelationId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Appends an event to {@code outbox_events} inside the caller's business transaction
 * (Transactional Outbox, FR-EVT-002). The event is validated against its JSON Schema first, so
 * an invalid event rolls back the business change instead of reaching consumers.
 *
 * <p>Every service that produces events owns this table in its own database:
 * <pre>
 * CREATE TABLE outbox_events (
 *   id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 *   event_id      UUID         NOT NULL UNIQUE,
 *   topic         VARCHAR(200) NOT NULL,
 *   message_key   VARCHAR(200) NOT NULL,
 *   event_type    VARCHAR(100) NOT NULL,
 *   envelope      JSONB        NOT NULL,
 *   status        VARCHAR(10)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT')),
 *   attempts      INT          NOT NULL DEFAULT 0,
 *   last_error    VARCHAR(1000),
 *   created_at    TIMESTAMPTZ  NOT NULL,
 *   sent_at       TIMESTAMPTZ
 * );
 * CREATE INDEX ix_outbox_pending ON outbox_events (id) WHERE status = 'PENDING';
 * </pre>
 */
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final EventSchemaValidator validator;
    private final String producer;
    private final Clock clock;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper objectMapper, EventSchemaValidator validator,
                        String producer, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.producer = producer;
        this.clock = clock;
    }

    /**
     * @param topic            target topic, e.g. {@code driver.events.v1}
     * @param key              Kafka key = aggregate ID, keeps one aggregate's events ordered
     * @param aggregateVersion version after the change; consumers drop older or equal versions
     * @return the event ID
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String topic, String key, String eventType, int eventVersion,
                       String aggregateId, long aggregateVersion, Object payload) {
        Instant now = clock.instant();
        String correlationId = CorrelationId.current();
        EventEnvelope envelope = new EventEnvelope(
                UuidV7.random(), eventType, eventVersion, now,
                correlationId != null ? correlationId : UuidV7.randomString(),
                producer, aggregateId, aggregateVersion, objectMapper.valueToTree(payload));

        JsonNode json = objectMapper.valueToTree(envelope);
        validator.validate(eventType, eventVersion, json);

        jdbc.update("""
                        INSERT INTO outbox_events (event_id, topic, message_key, event_type, envelope, created_at)
                        VALUES (?, ?, ?, ?, CAST(? AS JSONB), ?)
                        """,
                envelope.eventId(), topic, key, eventType, write(json), java.sql.Timestamp.from(now));
        return envelope.eventId();
    }

    private String write(JsonNode json) {
        try {
            return objectMapper.writeValueAsString(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize event", e);
        }
    }
}
