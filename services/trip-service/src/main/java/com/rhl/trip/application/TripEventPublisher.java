package com.rhl.trip.application;

import com.rhl.common.messaging.OutboxWriter;
import com.rhl.trip.domain.DriverOffer;
import com.rhl.trip.domain.Stop;
import com.rhl.trip.domain.Transition;
import com.rhl.trip.domain.Trip;
import com.rhl.trip.domain.TripStatus;
import com.rhl.trip.infrastructure.messaging.Topics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Maps trip and offer changes to events in {@code contracts/events/trip} and
 * {@code contracts/events/dispatch}, written to the outbox in the caller's transaction. Every
 * transition produces exactly one trip event; callers flush first so the version is final.
 */
@Component
@RequiredArgsConstructor
public class TripEventPublisher {

    private final OutboxWriter outbox;

    public void requested(Trip trip, Instant at) {
        Map<String, Object> payload = tripBase(trip);
        payload.put("serviceType", trip.getServiceType().name());
        payload.put("status", trip.getStatus().name());
        payload.put("pickup", stop(trip.getPickup()));
        payload.put("dropoff", stop(trip.getDropoff()));
        payload.put("matchingDeadline", trip.getMatchingDeadline().toString());
        payload.put("occurredAt", at.toString());
        trip(trip, "TripRequested", payload);
    }

    public void accepted(Trip trip, DriverOffer offer) {
        Map<String, Object> payload = tripBase(trip);
        payload.put("driverId", trip.getDriverId().toString());
        payload.put("offerId", offer.getId().toString());
        payload.put("serviceType", trip.getServiceType().name());
        payload.put("acceptedAt", trip.getAcceptedAt().toString());
        trip(trip, "TripAccepted", payload);
    }

    /** Announces one transition with the event that fits it (ACCEPTED is announced by {@link #accepted}). */
    public void transitioned(Trip trip, Transition transition) {
        if (transition.to() == TripStatus.COMPLETED) {
            completed(trip);
        } else if (transition.to() == TripStatus.CANCELLED) {
            cancelled(trip, transition);
        } else {
            statusChanged(trip, transition);
        }
    }

    public void offerCreated(Trip trip, DriverOffer offer) {
        Map<String, Object> payload = offerBase(offer);
        payload.put("serviceType", trip.getServiceType().name());
        payload.put("pickup", stop(trip.getPickup()));
        payload.put("estimatedPickupDistanceMeters", offer.getPickupDistanceMeters());
        payload.put("createdAt", offer.getCreatedAt().toString());
        payload.put("expiresAt", offer.getExpiresAt().toString());
        offer(offer, "DriverOfferCreated", payload);
    }

    public void offerExpired(DriverOffer offer) {
        Map<String, Object> payload = offerBase(offer);
        payload.put("expiredAt", offer.getRespondedAt().toString());
        offer(offer, "DriverOfferExpired", payload);
    }

    public void offerDeclined(DriverOffer offer) {
        Map<String, Object> payload = offerBase(offer);
        payload.put("declinedAt", offer.getRespondedAt().toString());
        offer(offer, "DriverOfferDeclined", payload);
    }

    /** @param reason {@code TRIP_CANCELLED} or {@code NO_DRIVER} */
    public void offerCancelled(DriverOffer offer, String reason) {
        Map<String, Object> payload = offerBase(offer);
        payload.put("reason", reason);
        payload.put("cancelledAt", offer.getRespondedAt().toString());
        offer(offer, "DriverOfferCancelled", payload);
    }

    private void statusChanged(Trip trip, Transition transition) {
        Map<String, Object> payload = tripBase(trip);
        payload.put("driverId", id(trip.getDriverId()));
        payload.put("oldStatus", transition.from().name());
        payload.put("newStatus", transition.to().name());
        payload.put("actorType", transition.actor().type().name());
        payload.put("actorId", id(transition.actor().id()));
        payload.put("reason", transition.reason());
        payload.put("occurredAt", transition.at().toString());
        trip(trip, "TripStatusChanged", payload);
    }

    private void completed(Trip trip) {
        Map<String, Object> payload = tripBase(trip);
        payload.put("driverId", trip.getDriverId().toString());
        payload.put("serviceType", trip.getServiceType().name());
        payload.put("pickup", stop(trip.getPickup()));
        payload.put("dropoff", stop(trip.getDropoff()));
        payload.put("acceptedAt", trip.getAcceptedAt().toString());
        payload.put("completedAt", trip.getCompletedAt().toString());
        trip(trip, "TripCompleted", payload);
    }

    private void cancelled(Trip trip, Transition transition) {
        Map<String, Object> payload = tripBase(trip);
        payload.put("driverId", id(trip.getDriverId()));
        payload.put("oldStatus", transition.from().name());
        payload.put("actorType", transition.actor().type().name());
        payload.put("actorId", id(transition.actor().id()));
        payload.put("reason", trip.getCancelReason().name());
        payload.put("cancelledAt", trip.getCancelledAt().toString());
        trip(trip, "TripCancelled", payload);
    }

    private void trip(Trip trip, String eventType, Map<String, Object> payload) {
        String tripId = trip.getId().toString();
        outbox.append(Topics.TRIP_EVENTS, tripId, eventType, 1, tripId, trip.getVersion(), payload);
    }

    private void offer(DriverOffer offer, String eventType, Map<String, Object> payload) {
        outbox.append(Topics.DISPATCH_OFFERS, offer.getDriverId().toString(), eventType, 1,
                offer.getId().toString(), offer.getVersion(), payload);
    }

    private static Map<String, Object> tripBase(Trip trip) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tripId", trip.getId().toString());
        payload.put("customerId", trip.getCustomerId().toString());
        return payload;
    }

    private static Map<String, Object> offerBase(DriverOffer offer) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("offerId", offer.getId().toString());
        payload.put("tripId", offer.getTripId().toString());
        payload.put("driverId", offer.getDriverId().toString());
        return payload;
    }

    private static Map<String, Object> stop(Stop stop) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("latitude", stop.latitude());
        value.put("longitude", stop.longitude());
        value.put("address", stop.address());
        return value;
    }

    private static String id(UUID id) {
        return id == null ? null : id.toString();
    }
}
