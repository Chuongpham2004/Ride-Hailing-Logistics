package com.rhl.realtime.infrastructure.messaging;

import com.rhl.common.messaging.EventEnvelope;
import com.rhl.common.messaging.EventSchemaValidator;
import com.rhl.common.messaging.InvalidEventException;
import com.rhl.common.web.CorrelationId;
import com.rhl.realtime.application.EventRouter;
import com.rhl.realtime.application.TripChannel;
import com.rhl.realtime.infrastructure.cache.TripParticipantStore;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the topics whose events reach clients. One listener for all of them: each instance has
 * its own consumer group, so it receives every partition of every topic. Events are checked
 * against their contract before anything of them reaches a client. Trip events also keep the
 * trip participants up to date, before they are pushed, so permission checks see them first.
 */
@Component
@RequiredArgsConstructor
public class DomainEventListener {

    public static final String LISTENER_ID = "realtime-domain-events";
    private static final String LOCATION_UPDATED = "DriverLocationUpdated";

    private final ObjectMapper objectMapper;
    private final EventSchemaValidator validator;
    private final EventRouter router;
    private final TripParticipantStore participants;
    private final TripChannel trips;

    @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = {Topics.DISPATCH_OFFERS, Topics.TRIP_EVENTS,
            Topics.PAYMENT_EVENTS, Topics.WALLET_EVENTS, Topics.LOCATION_UPDATES})
    public void onMessage(ConsumerRecord<String, String> record) throws JacksonException {
        JsonNode json = objectMapper.readTree(record.value());
        if (json == null || !json.isObject()) {
            throw new InvalidEventException("Event is not a JSON object");
        }
        String type = json.path("eventType").asString();
        boolean location = LOCATION_UPDATED.equals(type);
        if (!location && !EventRouter.isForwarded(type)) {
            return;
        }
        validator.validate(type, json.path("eventVersion").asInt(), json);
        EventEnvelope event = objectMapper.treeToValue(json, EventEnvelope.class);
        MDC.put(CorrelationId.MDC_KEY, event.correlationId());
        try {
            if (location) {
                trips.driverLocation(event);
                return;
            }
            if (Topics.TRIP_EVENTS.equals(record.topic())) {
                participants.apply(event);
            }
            router.route(event);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}
