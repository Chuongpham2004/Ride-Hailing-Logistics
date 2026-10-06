package com.rhl.common.messaging;

/** Kafka record headers set by the outbox relay so consumers can route without parsing. */
public final class EventHeaders {

    public static final String EVENT_ID = "eventId";
    public static final String EVENT_TYPE = "eventType";
    public static final String EVENT_VERSION = "eventVersion";
    public static final String CORRELATION_ID = "correlationId";

    private EventHeaders() {
    }
}
