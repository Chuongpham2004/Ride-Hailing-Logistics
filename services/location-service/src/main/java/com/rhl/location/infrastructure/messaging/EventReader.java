package com.rhl.location.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rhl.common.messaging.EventEnvelope;
import com.rhl.common.messaging.EventSchemaValidator;
import com.rhl.common.messaging.InvalidEventException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Parses and validates incoming envelopes against contracts/events. Contract violations throw
 * {@link InvalidEventException}, which the shared error handler sends straight to the DLT.
 */
@Component
@RequiredArgsConstructor
public class EventReader {

    private final ObjectMapper objectMapper;
    private final EventSchemaValidator validator;

    public EventEnvelope read(String value) throws JsonProcessingException {
        JsonNode json = objectMapper.readTree(value);
        if (json == null || !json.isObject()) {
            throw new InvalidEventException("Event is not a JSON object");
        }
        validator.validate(json.path("eventType").asText(), json.path("eventVersion").asInt(), json);
        return objectMapper.treeToValue(json, EventEnvelope.class);
    }

    /** Event type from the envelope, read without validation so unknown types can be skipped cheaply. */
    public String eventType(String value) throws JsonProcessingException {
        return objectMapper.readTree(value).path("eventType").asText();
    }
}
