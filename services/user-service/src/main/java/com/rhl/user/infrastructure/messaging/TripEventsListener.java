package com.rhl.user.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.rhl.common.messaging.EventEnvelope;
import com.rhl.common.web.CorrelationId;
import com.rhl.user.application.driver.TripAvailabilityProjection;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Consumes trip-service's dispatch.offers.v1 and trip.events.v1 for driver availability. */
@Component
@RequiredArgsConstructor
public class TripEventsListener {

    /** Only these move a driver; TripRequested and TripStatusChanged are skipped unparsed. */
    private static final Set<String> RELEVANT = Set.of("DriverOfferCreated", "DriverOfferExpired",
            "DriverOfferDeclined", "DriverOfferCancelled", "TripAccepted", "TripCompleted", "TripCancelled");

    private final EventReader reader;
    private final TripAvailabilityProjection projection;

    @KafkaListener(id = "user-trip-availability", idIsGroup = false,
            topics = {Topics.DISPATCH_OFFERS, Topics.TRIP_EVENTS})
    public void onMessage(ConsumerRecord<String, String> record) throws JsonProcessingException {
        if (!RELEVANT.contains(reader.eventType(record.value()))) {
            return;
        }
        EventEnvelope event = reader.read(record.value());
        MDC.put(CorrelationId.MDC_KEY, event.correlationId());
        try {
            projection.apply(event);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}
