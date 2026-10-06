package com.rhl.trip.application;

import com.rhl.common.id.UuidV7;
import com.rhl.common.security.CurrentUser;
import com.rhl.common.security.Role;
import com.rhl.common.web.ApiException;
import com.rhl.common.web.ErrorCode;
import com.rhl.trip.domain.Actor;
import com.rhl.trip.domain.CancelReason;
import com.rhl.trip.domain.DomainException;
import com.rhl.trip.domain.MatchingPolicy;
import com.rhl.trip.domain.ServiceType;
import com.rhl.trip.domain.Stop;
import com.rhl.trip.domain.Transition;
import com.rhl.trip.domain.Trip;
import com.rhl.trip.domain.TripStatus;
import com.rhl.trip.domain.TripStatusChange;
import com.rhl.trip.infrastructure.persistence.DriverOfferRepository;
import com.rhl.trip.infrastructure.persistence.IdempotencyKeyRepository;
import com.rhl.trip.infrastructure.persistence.TripRepository;
import com.rhl.trip.infrastructure.persistence.TripStatusChangeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Trip use cases for customers, assigned drivers and staff (FR-TRIP, FR-CAN). */
@Service
@RequiredArgsConstructor
public class TripService {

    /** Staff who may look up and cancel any trip (README §3); cancellations are recorded with the actor. */
    private static final Role[] STAFF = {Role.SUPPORT_STAFF, Role.ADMINISTRATOR};

    private final TripRepository trips;
    private final DriverOfferRepository offers;
    private final TripStatusChangeRepository history;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final TripEventPublisher events;
    private final ApplicationEventPublisher afterCommit;
    private final MatchingPolicy policy;
    private final Clock clock;

    public record CreateCommand(ServiceType serviceType, Stop pickup, Stop dropoff) {
    }

    /**
     * Creates the trip and starts matching (FR-TRIP-001). The same key with the same request
     * returns the trip created the first time; with a different request it is refused.
     *
     * <p>Quote validation (BR-005, BR-006) is added together with pricing-service.
     */
    @Transactional
    public TripViews.TripView create(UUID customerId, String idempotencyKey, String requestHash,
                                     CreateCommand command) {
        Instant now = clock.instant();
        String scope = "trip.create:" + customerId;
        UUID tripId = UuidV7.random();
        if (!idempotencyKeys.claim(scope, idempotencyKey, requestHash, tripId, now)) {
            IdempotencyKeyRepository.Entry entry = idempotencyKeys.find(scope, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Idempotency key vanished"));
            if (!entry.requestHash().equals(requestHash)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "This Idempotency-Key was already used for a different request");
            }
            return TripViews.TripView.of(trips.findById(entry.resourceId()).orElseThrow());
        }
        // The partial unique index enforces this too, for requests racing with different keys.
        if (trips.hasActiveTrip(customerId)) {
            throw DomainException.rule("You already have an active trip");
        }

        Trip trip = Trip.create(tripId, customerId, command.serviceType(), command.pickup(), command.dropoff(),
                policy, now);
        Transition created = new Transition(tripId, null, TripStatus.CREATED, Actor.customer(customerId), null, now);
        Transition matching = trip.startMatching(now);
        trips.saveAndFlush(trip);
        history.save(TripStatusChange.of(created));
        history.save(TripStatusChange.of(matching));
        events.requested(trip, now);
        afterCommit.publishEvent(new AfterCommit.Dispatch(tripId));
        return TripViews.TripView.of(trip);
    }

    @Transactional(readOnly = true)
    public TripViews.TripView get(CurrentUser user, UUID tripId) {
        return TripViews.TripView.of(visible(user, tripId));
    }

    @Transactional(readOnly = true)
    public List<TripViews.StatusChangeView> history(CurrentUser user, UUID tripId) {
        visible(user, tripId);
        return history.findByTripIdOrderByIdAsc(tripId).stream().map(TripViews.StatusChangeView::of).toList();
    }

    /** The caller's own trips, newest first: as a driver if they hold that role, otherwise as a customer. */
    @Transactional(readOnly = true)
    public TripViews.TripPage mine(CurrentUser user, UUID before, int limit) {
        boolean driver = user.has(Role.DRIVER);
        List<Trip> page;
        if (driver) {
            page = before == null ? trips.findDriverPage(user.id(), limit) : trips.findDriverPage(user.id(), before, limit);
        } else {
            page = before == null ? trips.findCustomerPage(user.id(), limit)
                    : trips.findCustomerPage(user.id(), before, limit);
        }
        UUID next = page.size() == limit ? page.getLast().getId() : null;
        return new TripViews.TripPage(page.stream().map(TripViews.TripView::of).toList(), next);
    }

    /**
     * Cancels on behalf of the customer, the assigned driver or staff (FR-CAN). Repeating a
     * cancellation returns the cancelled trip without a second event (idempotent).
     */
    @Transactional
    public TripViews.TripView cancel(CurrentUser user, UUID tripId, CancelReason reason, String note) {
        Trip trip = trips.findByIdForUpdate(tripId).orElseThrow(() -> ApiException.notFound("Trip"));
        Actor actor = actorFor(user, trip);
        if (trip.getStatus() == TripStatus.CANCELLED) {
            return TripViews.TripView.of(trip);
        }
        Instant now = clock.instant();
        Transition transition = trip.cancel(actor, reason, note, now);
        offers.findPendingByTripIdForUpdate(tripId).ifPresent(offer -> {
            offer.withdraw(now);
            offers.saveAndFlush(offer);
            events.offerCancelled(offer, "TRIP_CANCELLED");
            afterCommit.publishEvent(new AfterCommit.ReleaseHold(offer.getDriverId(), offer.getId()));
        });
        record(trip, transition);
        return TripViews.TripView.of(trip);
    }

    /**
     * PICKING_UP → ARRIVED → IN_TRIP → COMPLETED by the assigned driver. Repeating the step the
     * trip is already in returns it unchanged, so a retried completion never publishes a second
     * {@code TripCompleted} (BR-009).
     */
    @Transactional
    public TripViews.TripView advance(UUID driverId, UUID tripId, TripStatus target) {
        Trip trip = trips.findByIdForUpdate(tripId)
                .filter(t -> t.isAssignedTo(driverId))
                .orElseThrow(() -> ApiException.notFound("Trip"));
        if (trip.getStatus() == target) {
            return TripViews.TripView.of(trip);
        }
        record(trip, trip.advance(target, driverId, clock.instant()));
        return TripViews.TripView.of(trip);
    }

    private void record(Trip trip, Transition transition) {
        trips.saveAndFlush(trip);
        history.save(TripStatusChange.of(transition));
        events.transitioned(trip, transition);
    }

    /** 404 rather than 403 for trips the caller has nothing to do with, so IDs cannot be probed. */
    private Trip visible(CurrentUser user, UUID tripId) {
        Trip trip = trips.findById(tripId).orElseThrow(() -> ApiException.notFound("Trip"));
        actorFor(user, trip);
        return trip;
    }

    private static Actor actorFor(CurrentUser user, Trip trip) {
        if (trip.getCustomerId().equals(user.id())) {
            return Actor.customer(user.id());
        }
        if (trip.isAssignedTo(user.id())) {
            return Actor.driver(user.id());
        }
        if (user.hasAny(STAFF)) {
            return Actor.staff(user.id());
        }
        throw ApiException.notFound("Trip");
    }
}
