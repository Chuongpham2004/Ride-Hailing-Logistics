package com.rhl.pricing.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.rhl.common.messaging.EventEnvelope;
import com.rhl.common.web.CorrelationId;
import com.rhl.pricing.application.SurgeSignals;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/** Supply and demand signals for surge; other event types on these topics are skipped unparsed. */
@Component
@RequiredArgsConstructor
public class SurgeEventsListener {

    private final EventReader reader;
    private final SurgeSignals signals;

    @KafkaListener(id = "pricing-trip-demand", idIsGroup = false, topics = Topics.TRIP_EVENTS)
    public void onTripEvent(ConsumerRecord<String, String> record) throws JsonProcessingException {
        handle(record, "TripRequested", signals::onTripRequested);
    }

    @KafkaListener(id = "pricing-driver-supply", idIsGroup = false, topics = Topics.DRIVER_EVENTS)
    public void onDriverEvent(ConsumerRecord<String, String> record) throws JsonProcessingException {
        handle(record, "DriverAvailabilityChanged", signals::onAvailabilityChanged);
    }

    /** High-frequency stream (one report per driver every 3–5 s), so it gets its own consumers. */
    @KafkaListener(id = "pricing-location-supply", idIsGroup = false, topics = Topics.LOCATION_UPDATES,
            concurrency = "${rhl.kafka.location-concurrency}")
    public void onLocationUpdate(ConsumerRecord<String, String> record) throws JsonProcessingException {
        handle(record, "DriverLocationUpdated", signals::onLocationUpdated);
    }

    private void handle(ConsumerRecord<String, String> record, String eventType, Consumer<EventEnvelope> action)
            throws JsonProcessingException {
        if (!eventType.equals(reader.eventType(record.value()))) {
            return;
        }
        EventEnvelope event = reader.read(record.value());
        MDC.put(CorrelationId.MDC_KEY, event.correlationId());
        try {
            action.accept(event);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}
