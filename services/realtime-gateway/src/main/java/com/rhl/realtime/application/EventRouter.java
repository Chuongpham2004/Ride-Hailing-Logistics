package com.rhl.realtime.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rhl.common.messaging.EventEnvelope;
import com.rhl.common.security.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pushes domain events to the people they concern, on this instance's sessions (README §4.7:
 * every instance reads every event). Who receives what follows BR-013 and FR-RT: a driver gets
 * their offers and wallet changes, the customer and the assigned driver get the trip's status,
 * the customer gets payments and refunds. Recipients come from the event itself, never from a
 * client subscription, and each user only on sessions opened with the matching role.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventRouter {

    private enum Audience {
        DRIVER,
        CUSTOMER,
        CUSTOMER_AND_DRIVER
    }

    private static final Map<String, Audience> AUDIENCES = Map.ofEntries(
            Map.entry("DriverOfferCreated", Audience.DRIVER),
            Map.entry("DriverOfferExpired", Audience.DRIVER),
            Map.entry("DriverOfferDeclined", Audience.DRIVER),
            Map.entry("DriverOfferCancelled", Audience.DRIVER),
            Map.entry("TripRequested", Audience.CUSTOMER),
            Map.entry("TripAccepted", Audience.CUSTOMER_AND_DRIVER),
            Map.entry("TripStatusChanged", Audience.CUSTOMER_AND_DRIVER),
            Map.entry("TripCompleted", Audience.CUSTOMER_AND_DRIVER),
            Map.entry("TripCancelled", Audience.CUSTOMER_AND_DRIVER),
            Map.entry("PaymentSucceeded", Audience.CUSTOMER),
            Map.entry("PaymentFailed", Audience.CUSTOMER),
            Map.entry("RefundCompleted", Audience.CUSTOMER),
            Map.entry("DriverEarningPosted", Audience.DRIVER),
            Map.entry("WalletAdjusted", Audience.DRIVER));

    private final SessionRegistry sessions;
    private final Messages messages;

    public static boolean isForwarded(String eventType) {
        return AUDIENCES.containsKey(eventType);
    }

    /** @return how many sessions received the event */
    public int route(EventEnvelope event) {
        Audience audience = AUDIENCES.get(event.eventType());
        if (audience == null) {
            return 0;
        }
        JsonNode payload = event.payload();
        Set<Recipient> recipients = new LinkedHashSet<>(2);
        if (audience != Audience.DRIVER) {
            add(recipients, payload, "customerId", Role.CUSTOMER);
        }
        if (audience != Audience.CUSTOMER) {
            add(recipients, payload, "driverId", Role.DRIVER);
        }
        int delivered = 0;
        for (Recipient recipient : recipients) {
            for (ClientSession session : sessions.forUser(recipient.userId())) {
                if (session.has(recipient.role()) && session.send(message(event), messages.mapper())) {
                    delivered++;
                }
            }
        }
        log.debug("{} {} delivered to {} session(s)", event.eventType(), event.eventId(), delivered);
        return delivered;
    }

    /** One message object per session: {@link ClientSession#send} writes its own sequence into it. */
    private ObjectNode message(EventEnvelope event) {
        ObjectNode message = messages.create(event.eventId().toString(), upperSnake(event.eventType()),
                event.eventVersion(), event.payload().deepCopy());
        message.put("correlationId", event.correlationId());
        message.put("aggregateVersion", event.aggregateVersion());
        return message;
    }

    private static void add(Set<Recipient> recipients, JsonNode payload, String field, Role role) {
        if (payload.hasNonNull(field)) {
            try {
                recipients.add(new Recipient(UUID.fromString(payload.path(field).asText()), role));
            } catch (IllegalArgumentException e) {
                log.warn("Ignoring malformed {} in event payload", field);
            }
        }
    }

    /** {@code DriverOfferCreated} becomes {@code DRIVER_OFFER_CREATED}. */
    static String upperSnake(String eventType) {
        return eventType.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    private record Recipient(UUID userId, Role role) {
    }
}
