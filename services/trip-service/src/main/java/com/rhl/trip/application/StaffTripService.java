package com.rhl.trip.application;

import com.rhl.common.web.ApiException;
import com.rhl.trip.TripServiceProperties;
import com.rhl.trip.domain.ActorType;
import com.rhl.trip.domain.CodeUnlock;
import com.rhl.trip.domain.DeliveryProof;
import com.rhl.trip.domain.DomainException;
import com.rhl.trip.domain.DriverOffer;
import com.rhl.trip.domain.HandoverCode;
import com.rhl.trip.domain.OfferStatus;
import com.rhl.trip.domain.ServiceType;
import com.rhl.trip.domain.Trip;
import com.rhl.trip.domain.TripStatus;
import com.rhl.trip.infrastructure.persistence.CodeUnlockRepository;
import com.rhl.trip.infrastructure.persistence.DeliveryProofRepository;
import com.rhl.trip.infrastructure.persistence.DriverOfferRepository;
import com.rhl.trip.infrastructure.persistence.TripRepository;
import com.rhl.trip.infrastructure.persistence.TripStatusChangeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Trip lookup for support staff (README §3, FR-ADM-001/007): any trip, filtered and paged, and
 * one trip in full with its history, offers and delivery proof; unlocking a locked handover code. Opening a trip shows personal
 * data (recipient, route), so every detail view is audited (BR-014).
 */
@Service
@RequiredArgsConstructor
public class StaffTripService {

    private final TripRepository trips;
    private final TripStatusChangeRepository history;
    private final DriverOfferRepository offers;
    private final DeliveryProofRepository proofs;
    private final CodeUnlockRepository unlocks;
    private final TripService tripService;
    private final AuditLog audit;
    private final TripServiceProperties properties;
    private final Clock clock;

    /** All filters optional; {@code createdTo} is exclusive. */
    public record Filter(TripStatus status, UUID customerId, UUID driverId, ServiceType serviceType,
                         Instant createdFrom, Instant createdTo) {
    }

    public record OfferView(UUID id, UUID driverId, OfferStatus status, int pickupDistanceMeters, Instant createdAt,
                            Instant expiresAt, Instant respondedAt) {

        static OfferView of(DriverOffer o) {
            return new OfferView(o.getId(), o.getDriverId(), o.getStatus(), o.getPickupDistanceMeters(),
                    o.getCreatedAt(), o.getExpiresAt(), o.getRespondedAt());
        }
    }

    public record ProofView(String method, UUID driverId, Instant verifiedAt) {

        static ProofView of(DeliveryProof p) {
            return new ProofView(p.getMethod(), p.getDriverId(), p.getVerifiedAt());
        }
    }

    /**
     * Where a completed trip ended: the driver's last known position then, and its distance to
     * the planned drop-off.
     *
     * @param awayFromDropoff farther than {@code rhl.completion.max-distance-meters}: worth a look
     *                        (stopped early on request, or completed before arriving)
     */
    public record CompletionView(double latitude, double longitude, int distanceToDropoffMeters,
                                 boolean awayFromDropoff) {
    }

    /** A locked handover code support let the driver try again, and why. */
    public record CodeUnlockView(HandoverCode code, UUID staffId, int failuresBefore, String note,
                                 Instant createdAt) {

        static CodeUnlockView of(CodeUnlock u) {
            return new CodeUnlockView(u.getCode(), u.getStaffId(), u.getFailuresBefore(), u.getNote(),
                    u.getCreatedAt());
        }
    }

    /**
     * Handover codes are never shown to staff; the failure counters tell whether a code got
     * locked ({@code codeMaxAttempts} wrong entries) and support has to unlock it or cancel.
     */
    public record TripDetail(TripViews.TripView trip, List<TripViews.StatusChangeView> history,
                             List<OfferView> offers, ProofView deliveryProof, String cancelNote,
                             int pickupCodeFailures, int deliveryCodeFailures, int codeMaxAttempts,
                             List<CodeUnlockView> codeUnlocks, CompletionView completion) {
    }

    @Transactional(readOnly = true)
    public TripViews.TripPage search(Filter filter, UUID before, int limit) {
        List<Trip> page = trips.search(name(filter.status()), filter.customerId(), filter.driverId(),
                name(filter.serviceType()), filter.createdFrom(), filter.createdTo(), before, limit);
        UUID next = page.size() == limit ? page.getLast().getId() : null;
        return new TripViews.TripPage(page.stream().map(TripViews.TripView::of).toList(), next);
    }

    @Transactional
    public TripDetail detail(UUID staffId, UUID tripId) {
        Trip trip = trips.findById(tripId).orElseThrow(() -> ApiException.notFound("Trip"));
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("status", trip.getStatus().name());
        delta.put("serviceType", trip.getServiceType().name());
        audit.record(staffId, "TRIP_VIEWED", "TRIP", tripId, "SUCCESS", delta);
        return detailOf(trip);
    }

    /**
     * Lets the driver enter a locked handover code again after support has checked the situation
     * (for example confirmed the code with the customer by phone). Needs a note, kept with the
     * unlock record rather than in the audit delta (like a staff cancellation note); audited.
     */
    @Transactional
    public TripDetail unlockCode(UUID staffId, UUID tripId, HandoverCode code, String note) {
        if (note == null || note.strip().length() < TripService.MIN_STAFF_NOTE) {
            throw DomainException.rule("Unlocking a code needs a note of at least " + TripService.MIN_STAFF_NOTE
                    + " characters");
        }
        Trip trip = trips.findByIdForUpdate(tripId).orElseThrow(() -> ApiException.notFound("Trip"));
        Instant now = clock.instant();
        int failures = trip.unlockCode(code, properties.codes().maxAttempts(), now);
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("code", code.name());
        delta.put("failuresBefore", failures);
        delta.put("status", trip.getStatus().name());
        audit.record(staffId, "TRIP_CODE_UNLOCKED", "TRIP", tripId, "SUCCESS", delta);
        trips.saveAndFlush(trip);
        unlocks.save(CodeUnlock.record(tripId, code, staffId, failures, note.strip(), now));
        return detailOf(trip);
    }

    private TripDetail detailOf(Trip trip) {
        UUID tripId = trip.getId();
        return new TripDetail(tripService.view(trip, ActorType.STAFF),
                history.findByTripIdOrderByIdAsc(tripId).stream().map(TripViews.StatusChangeView::of).toList(),
                offers.findByTripIdOrderByCreatedAt(tripId).stream().map(OfferView::of).toList(),
                proofs.findByTripId(tripId).map(ProofView::of).orElse(null), trip.getCancelNote(),
                trip.getPickupCodeFailures(), trip.getDeliveryCodeFailures(), properties.codes().maxAttempts(),
                unlocks.findByTripIdOrderByCreatedAt(tripId).stream().map(CodeUnlockView::of).toList(),
                completion(trip));
    }

    /** Null when the trip is not completed, or no position was known at that moment. */
    private CompletionView completion(Trip trip) {
        if (trip.getCompletionDistanceMeters() == null) {
            return null;
        }
        int distance = trip.getCompletionDistanceMeters();
        return new CompletionView(trip.getCompletionLatitude(), trip.getCompletionLongitude(), distance,
                distance > properties.completion().maxDistanceMeters());
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
