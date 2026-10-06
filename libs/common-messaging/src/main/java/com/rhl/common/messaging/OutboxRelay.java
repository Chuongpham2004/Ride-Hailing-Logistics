package com.rhl.common.messaging;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes pending outbox rows to Kafka in id order. Several instances can run at once: each
 * claims its own rows with {@code FOR UPDATE SKIP LOCKED}. A failed send stops the batch, so
 * later events of the same aggregate are never published before an earlier one. Delivery is
 * at-least-once; consumers deduplicate by {@code eventId}.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final KafkaTemplate<String, String> kafka;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, KafkaTemplate<String, String> kafka,
                       OutboxProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.kafka = kafka;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${rhl.outbox.poll-interval:200ms}")
    public void poll() {
        int sent;
        do {
            try {
                Integer batch = tx.execute(status -> publishBatch());
                sent = batch == null ? 0 : batch;
            } catch (DataAccessException e) {
                // Database unreachable: rows stay PENDING and the next poll retries. One line per
                // poll instead of a stack trace every few hundred milliseconds.
                log.warn("Outbox relay cannot reach the database: {}", e.getMostSpecificCause().getMessage());
                return;
            }
        } while (sent == properties.batchSize());
    }

    /** @return number of rows published in this batch */
    int publishBatch() {
        List<Row> rows = jdbc.query("""
                        SELECT id, topic, message_key, event_type, envelope::text AS envelope,
                               envelope->>'eventId' AS event_id, envelope->>'eventVersion' AS event_version,
                               envelope->>'correlationId' AS correlation_id
                        FROM outbox_events
                        WHERE status = 'PENDING'
                        ORDER BY id
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED
                        """,
                (rs, i) -> new Row(rs.getLong("id"), rs.getString("topic"), rs.getString("message_key"),
                        rs.getString("event_type"), rs.getString("envelope"), rs.getString("event_id"),
                        rs.getString("event_version"), rs.getString("correlation_id")),
                properties.batchSize());

        int published = 0;
        for (Row row : rows) {
            try {
                kafka.send(toRecord(row)).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                markFailed(row, e);
                break;
            } catch (ExecutionException | TimeoutException e) {
                log.warn("Outbox publish failed for event {} ({}), will retry", row.eventId(), row.eventType(), e);
                markFailed(row, e);
                break;
            }
            jdbc.update("UPDATE outbox_events SET status = 'SENT', sent_at = ?, attempts = attempts + 1 WHERE id = ?",
                    java.sql.Timestamp.from(clock.instant()), row.id());
            published++;
        }
        return published;
    }

    private void markFailed(Row row, Exception e) {
        String reason = String.valueOf(e.getMessage());
        jdbc.update("UPDATE outbox_events SET attempts = attempts + 1, last_error = ? WHERE id = ?",
                reason.length() > 1000 ? reason.substring(0, 1000) : reason, row.id());
    }

    private static ProducerRecord<String, String> toRecord(Row row) {
        ProducerRecord<String, String> record = new ProducerRecord<>(row.topic(), row.key(), row.envelope());
        header(record, EventHeaders.EVENT_ID, row.eventId());
        header(record, EventHeaders.EVENT_TYPE, row.eventType());
        header(record, EventHeaders.EVENT_VERSION, row.eventVersion());
        header(record, EventHeaders.CORRELATION_ID, row.correlationId());
        return record;
    }

    private static void header(ProducerRecord<String, String> record, String name, String value) {
        if (value != null) {
            record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private record Row(long id, String topic, String key, String eventType, String envelope, String eventId,
                       String eventVersion, String correlationId) {
    }
}
