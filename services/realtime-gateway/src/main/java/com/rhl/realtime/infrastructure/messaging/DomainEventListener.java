package com.rhl.realtime.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rhl.common.messaging.EventEnvelope;
import com.rhl.common.messaging.EventSchemaValidator;
import com.rhl.common.messaging.InvalidEventException;
import com.rhl.common.web.CorrelationId;
import com.rhl.realtime.application.EventRouter;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reads the topics whose events are pushed to clients. One listener for all of them: each
 * instance has its own consumer group, so it receives every partition of every topic. Events are
 * checked against their contract before anything of them reaches a client.
 */
@Component
@RequiredArgsConstructor
public class DomainEventListener {

    public static final String LISTENER_ID = "realtime-domain-events";

    private final ObjectMapper objectMapper;
    private final EventSchemaValidator validator;
    private final EventRouter router;

    @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = {Topics.DISPATCH_OFFERS, Topics.TRIP_EVENTS,
            Topics.PAYMENT_EVENTS, Topics.WALLET_EVENTS})
    public void onMessage(ConsumerRecord<String, String> record) throws JsonProcessingException {
        JsonNode json = objectMapper.readTree(record.value());
        if (json == null || !json.isObject()) {
            throw new InvalidEventException("Event is not a JSON object");
        }
        String type = json.path("eventType").asText();
        if (!EventRouter.isForwarded(type)) {
            return;
        }
        validator.validate(type, json.path("eventVersion").asInt(), json);
        EventEnvelope event = objectMapper.treeToValue(json, EventEnvelope.class);
        MDC.put(CorrelationId.MDC_KEY, event.correlationId());
        try {
            router.route(event);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}
