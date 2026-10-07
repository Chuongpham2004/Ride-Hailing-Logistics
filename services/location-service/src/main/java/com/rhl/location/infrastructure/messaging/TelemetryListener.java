package com.rhl.location.infrastructure.messaging;

import com.rhl.common.messaging.EventEnvelope;
import com.rhl.common.web.CorrelationId;
import com.rhl.location.application.TelemetryService;
import com.rhl.location.domain.TelemetryRejectedException;
import com.rhl.location.domain.TelemetryReport;
import com.rhl.location.infrastructure.persistence.TelemetryHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** Consumes raw driver positions published by realtime-gateway (location.telemetry.raw.v1). */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelemetryListener {

    private final EventReader reader;
    private final TelemetryService telemetry;

    @KafkaListener(id = "location-telemetry", idIsGroup = false, topics = Topics.TELEMETRY_RAW,
            concurrency = "${rhl.kafka.telemetry-concurrency}")
    public void onMessage(ConsumerRecord<String, String> record) throws JacksonException {
        EventEnvelope event = reader.read(record.value());
        MDC.put(CorrelationId.MDC_KEY, event.correlationId());
        try {
            telemetry.ingest(toReport(event.payload()), TelemetryHistoryRepository.Source.STREAM);
        } catch (TelemetryRejectedException e) {
            // A rejected report is an expected outcome, not a failure: drop it rather than retry.
            log.debug("Dropped telemetry ({}): {}", e.reason(), e.getMessage());
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    private static TelemetryReport toReport(JsonNode payload) {
        return new TelemetryReport(
                UUID.fromString(payload.path("driverId").asString()),
                payload.path("sequence").asLong(),
                payload.path("latitude").asDouble(),
                payload.path("longitude").asDouble(),
                payload.path("accuracyMeters").asDouble(),
                payload.hasNonNull("headingDegrees") ? payload.path("headingDegrees").asDouble() : null,
                payload.hasNonNull("speedMetersPerSecond") ? payload.path("speedMetersPerSecond").asDouble() : null,
                Instant.parse(payload.path("deviceTimestamp").asString()));
    }
}
