package com.rhl.location.infrastructure.messaging;

import com.rhl.common.messaging.EventEnvelope;
import com.rhl.common.web.CorrelationId;
import com.rhl.location.application.PresenceService;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

/** Consumes driver.events.v1; only availability changes matter to location-service. */
@Component
@RequiredArgsConstructor
public class DriverEventsListener {

    private static final String AVAILABILITY_CHANGED = "DriverAvailabilityChanged";

    private final EventReader reader;
    private final PresenceService presence;

    @KafkaListener(id = "location-driver-events", idIsGroup = false, topics = Topics.DRIVER_EVENTS)
    public void onMessage(ConsumerRecord<String, String> record) throws JacksonException {
        // Other driver events (DriverApproved, DriverSuspended, ...) are not used here.
        if (!AVAILABILITY_CHANGED.equals(reader.eventType(record.value()))) {
            return;
        }
        EventEnvelope event = reader.read(record.value());
        MDC.put(CorrelationId.MDC_KEY, event.correlationId());
        try {
            presence.onAvailabilityChanged(event);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}
