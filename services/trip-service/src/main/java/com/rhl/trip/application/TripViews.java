package com.rhl.trip.application;

import com.rhl.trip.domain.ActorType;
import com.rhl.trip.domain.CancelReason;
import com.rhl.trip.domain.DriverOffer;
import com.rhl.trip.domain.FareSnapshot;
import com.rhl.trip.domain.OfferStatus;
import com.rhl.trip.domain.ServiceType;
import com.rhl.trip.domain.Stop;
import com.rhl.trip.domain.Trip;
import com.rhl.trip.domain.TripStatus;
import com.rhl.trip.domain.TripStatusChange;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Response bodies. Built inside the transaction so no entity escapes to the web layer. */
public final class TripViews {

    private TripViews() {
    }

    /**
     * {@code version} lets clients drop stale realtime updates after a REST snapshot (FR-RT, UC-07).
     * {@code fare} is the booked price (quote snapshot); {@code null} for trips booked before quotes.
     */
    public record TripView(UUID id, UUID customerId, UUID driverId, ServiceType serviceType, TripStatus status,
                           Stop pickup, Stop dropoff, FareSnapshot fare, Instant matchingDeadline,
                           CancelReason cancelReason, ActorType cancelledBy, Instant createdAt, Instant acceptedAt,
                           Instant completedAt, Instant cancelledAt, long version) {

        static TripView of(Trip t) {
            return new TripView(t.getId(), t.getCustomerId(), t.getDriverId(), t.getServiceType(), t.getStatus(),
                    t.getPickup(), t.getDropoff(), t.getFare(), t.getMatchingDeadline(), t.getCancelReason(),
                    t.getCancelledBy(), t.getCreatedAt(), t.getAcceptedAt(), t.getCompletedAt(), t.getCancelledAt(),
                    t.getVersion());
        }
    }

    /** What a driver sees of an offer: the pickup only, never the drop-off or the customer (BR-013). */
    public record OfferView(UUID id, UUID tripId, OfferStatus status, ServiceType serviceType, Stop pickup,
                            int estimatedPickupDistanceMeters, Instant createdAt, Instant expiresAt) {

        static OfferView of(DriverOffer o, Trip t) {
            return new OfferView(o.getId(), o.getTripId(), o.getStatus(), t.getServiceType(), t.getPickup(),
                    o.getPickupDistanceMeters(), o.getCreatedAt(), o.getExpiresAt());
        }
    }

    public record StatusChangeView(TripStatus fromStatus, TripStatus toStatus, ActorType actorType, String reason,
                                   Instant occurredAt) {

        static StatusChangeView of(TripStatusChange c) {
            return new StatusChangeView(c.getFromStatus(), c.getToStatus(), c.getActorType(), c.getReason(),
                    c.getOccurredAt());
        }
    }

    /** @param nextBefore pass as {@code before} to get the next page; {@code null} on the last page */
    public record TripPage(List<TripView> items, UUID nextBefore) {
    }
}
