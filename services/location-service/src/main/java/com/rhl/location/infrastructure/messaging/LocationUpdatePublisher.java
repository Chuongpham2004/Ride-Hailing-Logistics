package com.rhl.location.infrastructure.messaging;

import com.rhl.common.id.UuidV7;
import com.rhl.common.messaging.EventEnvelope;
import com.rhl.common.messaging.EventHeaders;
import com.rhl.common.messaging.EventSchemaValidator;
import com.rhl.common.web.CorrelationId;
import com.rhl.location.domain.LatestLocation;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publishes {@code DriverLocationUpdated} straight to Kafka. Location updates skip the outbox
 * (README §4.7): they are superseded every few seconds, so a lost one is replaced by the next
 * report instead of being retried.
 */
@Slf4j
@Component
public class LocationUpdatePublisher {

    private static final String EVENT_TYPE = "DriverLocationUpdated";
    private static final int EVENT_VERSION = 1;

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final EventSchemaValidator validator;
    private final String producer;

    public LocationUpdatePublisher(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper,
                                   EventSchemaValidator validator,
                                   @Value("${spring.application.name}") String producer) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.producer = producer;
    }

    public void publish(LatestLocation location) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("driverId", location.driverId().toString());
        payload.put("sequence", location.sequence());
        payload.put("latitude", location.latitude());
        payload.put("longitude", location.longitude());
        payload.put("accuracyMeters", location.accuracyMeters());
        payload.put("headingDegrees", location.headingDegrees());
        payload.put("speedMetersPerSecond", location.speedMetersPerSecond());
        payload.put("deviceTimestamp", location.deviceTime().toString());
        payload.put("serverTimestamp", location.serverTime().toString());

        String driverId = location.driverId().toString();
        String correlationId = CorrelationId.current() != null ? CorrelationId.current() : UuidV7.randomString();
        EventEnvelope envelope = new EventEnvelope(UuidV7.random(), EVENT_TYPE, EVENT_VERSION, location.serverTime(),
                correlationId, producer, driverId, location.sequence(), objectMapper.valueToTree(payload));
        JsonNode json = objectMapper.valueToTree(envelope);
        validator.validate(EVENT_TYPE, EVENT_VERSION, json);

        ProducerRecord<String, String> record = new ProducerRecord<>(Topics.LOCATION_UPDATES, driverId, write(json));
        header(record, EventHeaders.EVENT_ID, envelope.eventId().toString());
        header(record, EventHeaders.EVENT_TYPE, EVENT_TYPE);
        header(record, EventHeaders.EVENT_VERSION, Integer.toString(EVENT_VERSION));
        header(record, EventHeaders.CORRELATION_ID, correlationId);
        kafka.send(record).whenComplete((result, error) -> {
            if (error != null) {
                log.warn("Could not publish {} for sequence {}: {}", EVENT_TYPE, location.sequence(),
                        error.getMessage());
            }
        });
    }

    private String write(JsonNode json) {
        try {
            return objectMapper.writeValueAsString(json);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialize " + EVENT_TYPE, e);
        }
    }

    private static void header(ProducerRecord<String, String> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
