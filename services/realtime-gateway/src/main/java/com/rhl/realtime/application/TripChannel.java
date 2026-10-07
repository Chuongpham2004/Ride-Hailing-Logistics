package com.rhl.realtime.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rhl.common.messaging.EventEnvelope;
import com.rhl.common.security.Role;
import com.rhl.common.web.ErrorCode;
import com.rhl.realtime.RealtimeProperties;
import com.rhl.realtime.infrastructure.cache.TripParticipantStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Following a trip (FR-RT): a participant subscribes on their connection, and the trip's customer
 * then receives the assigned driver's validated position from {@code location.updates.v1}.
 * Permission is checked against the participants announced by trip-service, never against
 * anything the client claims, and ends once the trip has been over for the grace period (BR-013).
 */
@Slf4j
@Component
public class TripChannel {

    public static final String TRIP_SUBSCRIBED = "TRIP_SUBSCRIBED";
    public static final String TRIP_UNSUBSCRIBED = "TRIP_UNSUBSCRIBED";
    public static final String TRIP_DRIVER_LOCATION = "TRIP_DRIVER_LOCATION";
    private static final String[] OPTIONAL = {"headingDegrees", "speedMetersPerSecond"};

    /** Sessions of this instance that follow each trip. */
    private final Map<UUID, Set<ClientSession>> followers = new ConcurrentHashMap<>();
    private final TripParticipantStore store;
    private final Messages messages;
    private final ObjectMapper objectMapper;
    private final RealtimeProperties.Trip config;
    private final Clock clock;

    public TripChannel(TripParticipantStore store, Messages messages, ObjectMapper objectMapper,
                       RealtimeProperties properties, Clock clock) {
        this.store = store;
        this.messages = messages;
        this.objectMapper = objectMapper;
        this.config = properties.realtime().trip();
        this.clock = clock;
    }

    /**
     * Only the trip's customer (on a customer session) or its driver (on a driver session), while
     * the trip is followable. Any other trip, existing or not, is answered as not found.
     */
    public void subscribe(ClientSession session, UUID tripId, String inReplyTo) {
        if (!session.followedTrips().contains(tripId) && session.followedTrips().size() >= config.maxSubscriptions()) {
            error(session, ErrorCode.VALIDATION_ERROR, "At most " + config.maxSubscriptions()
                    + " trips can be followed per connection", inReplyTo);
            return;
        }
        Optional<TripParticipants> trip;
        try {
            trip = store.find(tripId);
        } catch (DataAccessException e) {
            log.warn("Trip lookup failed: {}", e.getMostSpecificCause().getMessage());
            error(session, ErrorCode.DEPENDENCY_UNAVAILABLE, "Try again shortly", inReplyTo);
            return;
        }
        Optional<TripParticipants.Participant> participant = trip.flatMap(t -> t.participant(session.getUserId()))
                .filter(p -> session.has(p == TripParticipants.Participant.CUSTOMER ? Role.CUSTOMER : Role.DRIVER));
        if (participant.isEmpty()) {
            error(session, ErrorCode.RESOURCE_NOT_FOUND, "Trip not found", inReplyTo);
            return;
        }
        if (!trip.get().isFollowable(clock.instant(), config.grace())) {
            error(session, ErrorCode.INVALID_TRIP_STATE, "The trip is over", inReplyTo);
            return;
        }
        session.follow(tripId);
        followers.computeIfAbsent(tripId, id -> ConcurrentHashMap.newKeySet()).add(session);
        ObjectNode data = messages.data();
        data.put("inReplyTo", inReplyTo);
        data.put("tripId", tripId.toString());
        data.put("participant", participant.get().name());
        data.put("status", trip.get().status());
        if (trip.get().driverId() != null) {
            data.put("driverId", trip.get().driverId().toString());
        }
        session.send(messages.create(TRIP_SUBSCRIBED, data), objectMapper);
    }

    public void unsubscribe(ClientSession session, UUID tripId, String inReplyTo) {
        stopFollowing(session, tripId);
        unsubscribed(session, tripId, "CLIENT_REQUEST", inReplyTo);
    }

    public void disconnected(ClientSession session) {
        for (UUID tripId : session.followedTrips()) {
            stopFollowing(session, tripId);
        }
    }

    /** A validated driver position: to the customer of the trip the driver serves, if they follow it. */
    public void driverLocation(EventEnvelope event) {
        if (followers.isEmpty()) {
            return;
        }
        JsonNode p = event.payload();
        UUID driverId = UUID.fromString(p.path("driverId").asText());
        Optional<UUID> tripId = store.currentTripOf(driverId);
        if (tripId.isEmpty() || !followers.containsKey(tripId.get())) {
            return;
        }
        TripParticipants trip = store.find(tripId.get()).orElse(null);
        if (trip == null || !driverId.equals(trip.driverId()) || !trip.isFollowable(clock.instant(), config.grace())) {
            return;
        }
        ObjectNode data = messages.data();
        data.put("tripId", trip.tripId().toString());
        data.put("driverId", driverId.toString());
        data.put("sequence", p.path("sequence").asLong());
        data.set("latitude", p.get("latitude"));
        data.set("longitude", p.get("longitude"));
        data.set("accuracyMeters", p.get("accuracyMeters"));
        for (String field : OPTIONAL) {
            if (p.hasNonNull(field)) {
                data.set(field, p.get(field));
            }
        }
        data.put("serverTimestamp", p.path("serverTimestamp").asText());
        for (ClientSession session : followers.getOrDefault(trip.tripId(), Set.of())) {
            if (session.getUserId().equals(trip.customerId()) && session.has(Role.CUSTOMER)) {
                ObjectNode message = messages.create(event.eventId().toString(), TRIP_DRIVER_LOCATION, 1,
                        data.deepCopy());
                message.put("correlationId", event.correlationId());
                message.put("aggregateVersion", event.aggregateVersion());
                session.send(message, objectMapper);
            }
        }
    }

    /** Ends following for trips over for longer than the grace period (BR-013). */
    public void sweep() {
        Instant now = clock.instant();
        for (UUID tripId : List.copyOf(followers.keySet())) {
            boolean over;
            try {
                over = store.find(tripId).map(t -> !t.isFollowable(now, config.grace())).orElse(true);
            } catch (DataAccessException e) {
                log.warn("Trip lookup failed: {}", e.getMostSpecificCause().getMessage());
                return;
            }
            if (over) {
                Set<ClientSession> sessions = followers.remove(tripId);
                for (ClientSession session : sessions == null ? Set.<ClientSession>of() : sessions) {
                    session.unfollow(tripId);
                    unsubscribed(session, tripId, "TRIP_ENDED", null);
                }
            }
        }
    }

    private void stopFollowing(ClientSession session, UUID tripId) {
        session.unfollow(tripId);
        followers.computeIfPresent(tripId, (id, sessions) -> {
            sessions.remove(session);
            return sessions.isEmpty() ? null : sessions;
        });
    }

    private void unsubscribed(ClientSession session, UUID tripId, String reason, String inReplyTo) {
        ObjectNode data = messages.data();
        if (inReplyTo != null) {
            data.put("inReplyTo", inReplyTo);
        }
        data.put("tripId", tripId.toString());
        data.put("reason", reason);
        session.send(messages.create(TRIP_UNSUBSCRIBED, data), objectMapper);
    }

    private void error(ClientSession session, ErrorCode code, String text, String inReplyTo) {
        session.send(messages.error(code.name(), text, inReplyTo), objectMapper);
    }
}
