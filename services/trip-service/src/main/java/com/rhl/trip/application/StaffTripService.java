package com.rhl.trip.application;

import com.rhl.common.web.ApiException;
import com.rhl.trip.domain.ActorType;
import com.rhl.trip.domain.DeliveryProof;
import com.rhl.trip.domain.DriverOffer;
import com.rhl.trip.domain.OfferStatus;
import com.rhl.trip.domain.ServiceType;
import com.rhl.trip.domain.Trip;
import com.rhl.trip.domain.TripStatus;
import com.rhl.trip.infrastructure.persistence.DeliveryProofRepository;
import com.rhl.trip.infrastructure.persistence.DriverOfferRepository;
import com.rhl.trip.infrastructure.persistence.TripRepository;
import com.rhl.trip.infrastructure.persistence.TripStatusChangeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Trip lookup for support staff (README §3, FR-ADM-001/007): any trip, filtered and paged, and
 * one trip in full with its history, offers and delivery proof. Opening a trip shows personal
 * data (recipient, route), so every detail view is audited (BR-014).
 */
@Service
@RequiredArgsConstructor
public class StaffTripService {

    private final TripRepository trips;
    private final TripStatusChangeRepository history;
    private final DriverOfferRepository offers;
    private final DeliveryProofRepository proofs;
    private final TripService tripService;
    private final AuditLog audit;

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
     * Handover codes are never shown to staff; the failure counters tell whether a code got
     * locked and the trip needs an exceptional cancellation.
     */
    public record TripDetail(TripViews.TripView trip, List<TripViews.StatusChangeView> history,
                             List<OfferView> offers, ProofView deliveryProof, String cancelNote,
                             int pickupCodeFailures, int deliveryCodeFailures) {
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
        return new TripDetail(tripService.view(trip, ActorType.STAFF),
                history.findByTripIdOrderByIdAsc(tripId).stream().map(TripViews.StatusChangeView::of).toList(),
                offers.findByTripIdOrderByCreatedAt(tripId).stream().map(OfferView::of).toList(),
                proofs.findByTripId(tripId).map(ProofView::of).orElse(null), trip.getCancelNote(),
                trip.getPickupCodeFailures(), trip.getDeliveryCodeFailures());
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
