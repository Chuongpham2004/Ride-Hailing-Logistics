package com.rhl.payment.infrastructure.messaging;

import com.rhl.common.messaging.EventEnvelope;
import com.rhl.common.messaging.EventSchemaValidator;
import com.rhl.common.messaging.InvalidEventException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Parses and validates incoming envelopes against contracts/events. Contract violations throw
 * {@link InvalidEventException}, which the shared error handler sends straight to the DLT.
 */
@Component
@RequiredArgsConstructor
public class EventReader {

    private final ObjectMapper objectMapper;
    private final EventSchemaValidator validator;

    public EventEnvelope read(String value) throws JacksonException {
        JsonNode json = objectMapper.readTree(value);
        if (json == null || !json.isObject()) {
            throw new InvalidEventException("Event is not a JSON object");
        }
        validator.validate(json.path("eventType").asString(), json.path("eventVersion").asInt(), json);
        return objectMapper.treeToValue(json, EventEnvelope.class);
    }

    /** Event type from the envelope, read without validation so unknown types can be skipped cheaply. */
    public String eventType(String value) throws JacksonException {
        return objectMapper.readTree(value).path("eventType").asString();
    }
}
