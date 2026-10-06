package com.rhl.trip.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Trip aggregate. Every status change goes through {@link TripStateMachine} and returns the
 * {@link Transition} the caller records in the history and the outbox (README §6).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "trips")
public class Trip {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "driver_id")
    private UUID driverId;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", nullable = false)
    private ServiceType serviceType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TripStatus status;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "latitude", column = @Column(name = "pickup_latitude")),
            @AttributeOverride(name = "longitude", column = @Column(name = "pickup_longitude")),
            @AttributeOverride(name = "address", column = @Column(name = "pickup_address"))})
    private Stop pickup;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "latitude", column = @Column(name = "dropoff_latitude")),
            @AttributeOverride(name = "longitude", column = @Column(name = "dropoff_longitude")),
            @AttributeOverride(name = "address", column = @Column(name = "dropoff_address"))})
    private Stop dropoff;

    @Column(name = "matching_radius_meters", nullable = false)
    private int matchingRadiusMeters;

    @Column(name = "matching_deadline", nullable = false)
    private Instant matchingDeadline;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason")
    private CancelReason cancelReason;

    @Column(name = "cancel_note")
    private String cancelNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancelled_by")
    private ActorType cancelledBy;

    /**
     * Also the {@code aggregateVersion} of trip events, so consumers can drop stale ones (FR-EVT-006).
     * {@code null} until persisted, so Spring Data inserts instead of merging.
     */
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    public static Trip create(UUID id, UUID customerId, ServiceType serviceType, Stop pickup, Stop dropoff,
                              MatchingPolicy policy, Instant now) {
        Trip trip = new Trip();
        trip.id = Objects.requireNonNull(id);
        trip.customerId = Objects.requireNonNull(customerId);
        trip.serviceType = Objects.requireNonNull(serviceType);
        trip.pickup = Objects.requireNonNull(pickup);
        trip.dropoff = Objects.requireNonNull(dropoff);
        trip.status = TripStatus.CREATED;
        trip.matchingRadiusMeters = policy.initialRadiusMeters();
        trip.matchingDeadline = now.plus(policy.matchingTimeout());
        trip.createdAt = now;
        trip.updatedAt = now;
        return trip;
    }

    /** The trip and its stops were validated: start looking for a driver. */
    public Transition startMatching(Instant now) {
        return moveTo(TripStatus.MATCHING, Actor.SYSTEM, null, now);
    }

    /** The driver won the offer (FR-MAT, CON-06); the database still has the final say on uniqueness. */
    public Transition assign(UUID driver, Instant now) {
        Transition transition = moveTo(TripStatus.ACCEPTED, Actor.driver(driver), null, now);
        driverId = driver;
        acceptedAt = now;
        return transition;
    }

    /** PICKING_UP, ARRIVED, IN_TRIP or COMPLETED, reported by the assigned driver. */
    public Transition advance(TripStatus target, UUID driver, Instant now) {
        if (!isAssignedTo(driver)) {
            throw DomainException.invalidTripState("The trip is not assigned to this driver");
        }
        Transition transition = moveTo(target, Actor.driver(driver), null, now);
        if (target == TripStatus.COMPLETED) {
            completedAt = now;
        }
        return transition;
    }

    public Transition cancel(Actor actor, CancelReason reason, String note, Instant now) {
        Objects.requireNonNull(reason, "A cancellation reason is required");
        if (actor.type() == ActorType.DRIVER && !isAssignedTo(actor.id())) {
            throw DomainException.invalidTripState("The trip is not assigned to this driver");
        }
        Transition transition = moveTo(TripStatus.CANCELLED, actor, reason.name(), now);
        cancelReason = reason;
        cancelNote = note == null || note.isBlank() ? null : note.strip();
        cancelledBy = actor.type();
        cancelledAt = now;
        return transition;
    }

    /** Matching ran out of time without an accepted offer (README §5.2 step 7). */
    public Transition giveUp(Instant now) {
        return moveTo(TripStatus.NO_DRIVER, Actor.SYSTEM, "Matching deadline reached", now);
    }

    public boolean isMatchingOverdue(Instant now) {
        return status == TripStatus.MATCHING && !now.isBefore(matchingDeadline);
    }

    /**
     * Grows the search radius by one step (README §5.2 step 6).
     *
     * @return {@code false} when the radius is already at its maximum
     */
    public boolean widenSearch(MatchingPolicy policy, Instant now) {
        int next = policy.nextRadius(matchingRadiusMeters);
        if (next == matchingRadiusMeters) {
            return false;
        }
        matchingRadiusMeters = next;
        updatedAt = now;
        return true;
    }

    public boolean isAssignedTo(UUID driver) {
        return driverId != null && driverId.equals(driver);
    }

    private Transition moveTo(TripStatus target, Actor actor, String reason, Instant now) {
        TripStateMachine.check(status, target, actor.type());
        TripStatus from = status;
        status = target;
        updatedAt = now;
        return new Transition(id, from, target, actor, reason, now);
    }
}
