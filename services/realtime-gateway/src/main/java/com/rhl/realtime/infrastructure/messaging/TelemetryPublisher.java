package com.rhl.realtime.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rhl.common.id.UuidV7;
import com.rhl.common.messaging.EventEnvelope;
import com.rhl.common.messaging.EventHeaders;
import com.rhl.common.messaging.EventSchemaValidator;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Forwards a driver's {@code DRIVER_LOCATION_UPDATED} as {@code DriverLocationReported} straight
 * to Kafka (README §4.7: telemetry skips the outbox; a lost report is replaced by the next one).
 * The driver ID is the session's user, never a value from the client (BR-013).
 */
@Slf4j
@Component
public class TelemetryPublisher {

    private static final String EVENT_TYPE = "DriverLocationReported";
    private static final int EVENT_VERSION = 1;
    private static final String[] OPTIONAL = {"headingDegrees", "speedMetersPerSecond"};

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final EventSchemaValidator validator;
    private final String producer;

    public TelemetryPublisher(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper,
                              EventSchemaValidator validator, @Value("${spring.application.name}") String producer) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.producer = producer;
    }

    /**
     * @param message a {@code DRIVER_LOCATION_UPDATED} message already valid against its schema
     */
    public void publish(UUID driverId, JsonNode message, String correlationId, Instant receivedAt) {
        JsonNode data = message.path("data");
        long sequence = message.path("sequence").asLong();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("driverId", driverId.toString());
        payload.put("messageId", message.path("messageId").asText());
        payload.put("sequence", sequence);
        payload.set("latitude", data.get("latitude"));
        payload.set("longitude", data.get("longitude"));
        payload.set("accuracyMeters", data.get("accuracyMeters"));
        for (String field : OPTIONAL) {
            if (data.hasNonNull(field)) {
                payload.set(field, data.get(field));
            }
        }
        payload.put("deviceTimestamp", data.path("deviceTimestamp").asText());
        payload.put("receivedAt", receivedAt.toString());

        String key = driverId.toString();
        EventEnvelope envelope = new EventEnvelope(UuidV7.random(), EVENT_TYPE, EVENT_VERSION, receivedAt,
                correlationId, producer, key, sequence, payload);
        JsonNode json = objectMapper.valueToTree(envelope);
        validator.validate(EVENT_TYPE, EVENT_VERSION, json);

        ProducerRecord<String, String> record = new ProducerRecord<>(Topics.TELEMETRY_RAW, key, write(json));
        header(record, EventHeaders.EVENT_ID, envelope.eventId().toString());
        header(record, EventHeaders.EVENT_TYPE, EVENT_TYPE);
        header(record, EventHeaders.EVENT_VERSION, Integer.toString(EVENT_VERSION));
        header(record, EventHeaders.CORRELATION_ID, correlationId);
        kafka.send(record).whenComplete((result, error) -> {
            if (error != null) {
                log.warn("Could not publish {} sequence {}: {}", EVENT_TYPE, sequence, error.getMessage());
            }
        });
    }

    private String write(JsonNode json) {
        try {
            return objectMapper.writeValueAsString(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize " + EVENT_TYPE, e);
        }
    }

    private static void header(ProducerRecord<String, String> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
