package com.rhl.trip.application;

import com.rhl.common.web.ApiException;
import com.rhl.trip.domain.DomainException;
import com.rhl.trip.domain.DriverCandidate;
import com.rhl.trip.domain.DriverOffer;
import com.rhl.trip.domain.MatchingPolicy;
import com.rhl.trip.domain.OfferStatus;
import com.rhl.trip.domain.ServiceType;
import com.rhl.trip.domain.Stop;
import com.rhl.trip.domain.Transition;
import com.rhl.trip.domain.Trip;
import com.rhl.trip.domain.TripStatus;
import com.rhl.trip.domain.TripStatusChange;
import com.rhl.trip.infrastructure.persistence.DriverOfferRepository;
import com.rhl.trip.infrastructure.persistence.TripRepository;
import com.rhl.trip.infrastructure.persistence.TripStatusChangeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Transactional steps of dispatch (README §4.8, §5.2). Locks are always taken trip first, then
 * offer. Correctness rests on PostgreSQL: row locks, the pending-offer unique indexes and the
 * active-driver unique index; Redis holds only make conflicts rare.
 */
@Service
@RequiredArgsConstructor
public class OfferService {

    private final TripRepository trips;
    private final DriverOfferRepository offers;
    private final TripStatusChangeRepository history;
    private final TripEventPublisher events;
    private final ApplicationEventPublisher afterCommit;
    private final MatchingPolicy policy;
    private final Clock clock;

    /** What a dispatch round needs to know about a trip that is waiting for an offer. */
    public record Round(UUID tripId, ServiceType serviceType, Stop pickup, int radiusMeters, Set<UUID> excluded) {
    }

    // ---- driver actions ----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<TripViews.OfferView> pendingFor(UUID driverId) {
        return offers.findPendingByDriverId(driverId).stream()
                .map(o -> TripViews.OfferView.of(o, trips.findById(o.getTripId()).orElseThrow()))
                .toList();
    }

    /**
     * Atomic accept (CON-06, UC-06): the offer must still be open, the trip still MATCHING and the
     * driver free of other active trips. Accepting an offer this driver already won returns the
     * same trip again.
     */
    @Transactional
    public TripViews.TripView accept(UUID driverId, UUID offerId) {
        Trip trip = trips.findByIdForUpdate(tripOf(driverId, offerId)).orElseThrow();
        DriverOffer offer = offers.findByIdForUpdate(offerId).orElseThrow();
        if (offer.getStatus() == OfferStatus.ACCEPTED) {
            return TripViews.TripView.of(trip);
        }
        Instant now = clock.instant();
        offer.accept(now);
        Transition transition;
        try {
            transition = trip.assign(driverId, now);
        } catch (DomainException e) {
            throw DomainException.offerExpired("The trip is no longer looking for a driver");
        }
        try {
            offers.saveAndFlush(offer);
            trips.saveAndFlush(trip);
        } catch (DataIntegrityViolationException e) {
            // ux_trips_active_driver: the driver is already on another trip (BR-002).
            throw DomainException.driverAlreadyAssigned("The driver already has an active trip");
        }
        history.save(TripStatusChange.of(transition));
        events.accepted(trip, offer);
        afterCommit.publishEvent(new AfterCommit.ReleaseHold(driverId, offerId));
        return TripViews.TripView.of(trip);
    }

    /** Declining twice is harmless; the trip moves on to the next candidate at once. */
    @Transactional
    public TripViews.OfferView decline(UUID driverId, UUID offerId) {
        Trip trip = trips.findByIdForUpdate(tripOf(driverId, offerId)).orElseThrow();
        DriverOffer offer = offers.findByIdForUpdate(offerId).orElseThrow();
        if (offer.getStatus() != OfferStatus.DECLINED) {
            offer.decline(clock.instant());
            offers.saveAndFlush(offer);
            events.offerDeclined(offer);
            afterCommit.publishEvent(new AfterCommit.ReleaseHold(driverId, offerId));
            afterCommit.publishEvent(new AfterCommit.Dispatch(trip.getId()));
        }
        return TripViews.OfferView.of(offer, trip);
    }

    // ---- dispatcher steps --------------------------------------------------------------------

    /**
     * Starts a round, or ends matching when the deadline passed with no open offer
     * (README §5.2 step 7).
     *
     * @return empty when the trip needs no offer right now
     */
    @Transactional
    public Optional<Round> prepareRound(UUID tripId) {
        Trip trip = trips.findByIdForUpdate(tripId).orElse(null);
        if (trip == null || trip.getStatus() != TripStatus.MATCHING || offers.hasPendingOffer(tripId)) {
            return Optional.empty();
        }
        if (trip.isMatchingOverdue(clock.instant())) {
            Transition transition = trip.giveUp(clock.instant());
            trips.saveAndFlush(trip);
            history.save(TripStatusChange.of(transition));
            events.transitioned(trip, transition);
            return Optional.empty();
        }
        return Optional.of(new Round(tripId, trip.getServiceType(), trip.getPickup(), trip.getMatchingRadiusMeters(),
                offers.findOfferedDriverIds(tripId)));
    }

    /** Drivers among {@code driverIds} that are on an active trip or hold an open offer. */
    @Transactional(readOnly = true)
    public Set<UUID> busy(Collection<UUID> driverIds) {
        if (driverIds.isEmpty()) {
            return Set.of();
        }
        Set<UUID> busy = new HashSet<>(trips.findDriversOnActiveTrips(driverIds));
        busy.addAll(offers.findDriversWithPendingOffer(driverIds));
        return busy;
    }

    /**
     * Opens an offer for a driver the caller already holds in Redis. A concurrent round that got
     * there first makes this a no-op (or a unique-index violation, rolled back by the caller).
     *
     * @return {@code false} when the trip no longer needs this offer
     */
    @Transactional
    public boolean open(UUID tripId, UUID offerId, DriverCandidate candidate) {
        Trip trip = trips.findByIdForUpdate(tripId).orElse(null);
        Instant now = clock.instant();
        if (trip == null || trip.getStatus() != TripStatus.MATCHING || trip.isMatchingOverdue(now)
                || offers.hasPendingOffer(tripId)) {
            return false;
        }
        DriverOffer offer = DriverOffer.create(offerId, tripId, candidate, policy.offerTimeout(), now);
        offers.saveAndFlush(offer);
        events.offerCreated(trip, offer);
        return true;
    }

    /** @return {@code false} when the radius is already at its maximum */
    @Transactional
    public boolean widenSearch(UUID tripId) {
        Trip trip = trips.findByIdForUpdate(tripId).orElse(null);
        if (trip == null || trip.getStatus() != TripStatus.MATCHING || !trip.widenSearch(policy, clock.instant())) {
            return false;
        }
        trips.save(trip);
        return true;
    }

    /** Expires overdue offers claimed by this instance and queues the next round for their trips. */
    @Transactional
    public int expireDue(int limit) {
        Instant now = clock.instant();
        List<DriverOffer> due = offers.lockExpired(now, limit);
        for (DriverOffer offer : due) {
            offer.expire(now);
            offers.saveAndFlush(offer);
            events.offerExpired(offer);
            afterCommit.publishEvent(new AfterCommit.ReleaseHold(offer.getDriverId(), offer.getId()));
            afterCommit.publishEvent(new AfterCommit.Dispatch(offer.getTripId()));
        }
        return due.size();
    }

    /** 404 for offers of other drivers, so offer IDs cannot be probed. */
    private UUID tripOf(UUID driverId, UUID offerId) {
        return offers.findTripIdOfDriverOffer(offerId, driverId).orElseThrow(() -> ApiException.notFound("Offer"));
    }
}
