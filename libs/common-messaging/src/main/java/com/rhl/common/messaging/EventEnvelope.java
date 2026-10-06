package com.rhl.common.messaging;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Wire format of every domain event (README §9, {@code contracts/events/envelope.v1.schema.json}).
 * Consumers read {@code payload} into their own type and ignore unknown fields.
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String correlationId,
        String producer,
        String aggregateId,
        long aggregateVersion,
        JsonNode payload) {
}
