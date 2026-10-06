package com.rhl.pricing.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.rhl.common.messaging.EventEnvelope;
import com.rhl.common.web.CorrelationId;
import com.rhl.pricing.application.SettlementService;
import com.rhl.pricing.application.SurgeSignals;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The single listener on trip.events.v1 (two listeners in one consumer group would split its
 * partitions): demand for surge, and settlement of completed and cancelled trips.
 */
@Component
@RequiredArgsConstructor
public class TripEventsListener {

    private final EventReader reader;
    private final SurgeSignals surge;
    private final SettlementService settlement;

    @KafkaListener(id = "pricing-trip-events", idIsGroup = false, topics = Topics.TRIP_EVENTS)
    public void onMessage(ConsumerRecord<String, String> record) throws JsonProcessingException {
        String type = reader.eventType(record.value());
        if (!"TripRequested".equals(type) && !"TripCompleted".equals(type) && !"TripCancelled".equals(type)) {
            return;
        }
        EventEnvelope event = reader.read(record.value());
        MDC.put(CorrelationId.MDC_KEY, event.correlationId());
        try {
            switch (type) {
                case "TripRequested" -> surge.onTripRequested(event);
                case "TripCompleted" -> settlement.onTripCompleted(event);
                default -> settlement.onTripCancelled(event);
            }
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}
