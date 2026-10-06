package com.rhl.user.domain.driver;

import com.rhl.user.domain.DomainException;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Driver profile aggregate: review lifecycle (FR-DRV) and availability (README §5.3). OFFLINE ↔
 * AVAILABLE is decided here; OFFERED and BUSY are decided by trip-service and projected from its
 * events. The driver ID equals the user ID of the driver account.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "driver_profiles")
public class DriverProfile {

    @Id
    @Column(name = "driver_id")
    private UUID driverId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "driver_profile_service_types", joinColumns = @JoinColumn(name = "driver_id"))
    @Column(name = "service_type")
    @Enumerated(EnumType.STRING)
    private Set<ServiceType> serviceTypes = EnumSet.noneOf(ServiceType.class);

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false)
    private ReviewStatus reviewStatus;

    @Column(name = "status_reason")
    private String statusReason;

    /** Incremented on every submission; review decisions name the version they looked at. */
    @Column(name = "profile_version", nullable = false)
    private int profileVersion;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Availability availability;

    @Column(name = "active_vehicle_id")
    private UUID activeVehicleId;

    @Column(name = "availability_changed_at")
    private Instant availabilityChangedAt;

    /** Services chosen when going online; restored when a trip or offer ends. */
    @Convert(converter = ServiceTypesConverter.class)
    @Column(name = "online_service_types")
    private Set<ServiceType> onlineServiceTypes = EnumSet.noneOf(ServiceType.class);

    /** The open offer that made the driver OFFERED; other offers' events are ignored. */
    @Column(name = "current_offer_id")
    private UUID currentOfferId;

    @Column(name = "current_offer_expires_at")
    private Instant currentOfferExpiresAt;

    /** The offer behind the current or last trip; its late DriverOfferCreated is ignored. */
    @Column(name = "accepted_offer_id")
    private UUID acceptedOfferId;

    /** The trip that made the driver BUSY; other trips' events are ignored. */
    @Column(name = "current_trip_id")
    private UUID currentTripId;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static DriverProfile create(UUID driverId, String fullName, LocalDate dateOfBirth,
                                       Set<ServiceType> serviceTypes, Instant now) {
        DriverProfile profile = new DriverProfile();
        profile.driverId = driverId;
        profile.reviewStatus = ReviewStatus.DRAFT;
        profile.availability = Availability.OFFLINE;
        profile.createdAt = now;
        profile.applyDetails(fullName, dateOfBirth, serviceTypes, now);
        return profile;
    }

    public void updateDetails(String fullName, LocalDate dateOfBirth, Set<ServiceType> serviceTypes, Instant now) {
        requireEditable();
        applyDetails(fullName, dateOfBirth, serviceTypes, now);
    }

    public void requireEditable() {
        if (!reviewStatus.isEditable()) {
            throw DomainException.invalidState("The profile cannot be changed while it is " + reviewStatus);
        }
    }

    // ---- review lifecycle ---------------------------------------------------------------

    /** @param problems result of {@link DocumentRequirements#profileProblems}; must be empty */
    public void submit(List<String> problems, Instant now) {
        requireEditable();
        requireNoProblems(problems);
        reviewStatus = ReviewStatus.PENDING_REVIEW;
        profileVersion++;
        submittedAt = now;
        statusReason = null;
        updatedAt = now;
    }

    public void approve(int reviewedVersion, List<String> problems, Instant now) {
        requireUnderReview(reviewedVersion);
        requireNoProblems(problems);
        reviewStatus = ReviewStatus.APPROVED;
        statusReason = null;
        updatedAt = now;
    }

    public void reject(int reviewedVersion, String reason, Instant now) {
        requireUnderReview(reviewedVersion);
        statusReason = requireReason(reason);
        reviewStatus = ReviewStatus.REJECTED;
        updatedAt = now;
    }

    public void requestChanges(int reviewedVersion, String reason, Instant now) {
        requireUnderReview(reviewedVersion);
        statusReason = requireReason(reason);
        reviewStatus = ReviewStatus.DRAFT;
        updatedAt = now;
    }

    /**
     * Blocks the driver from working. An AVAILABLE driver is taken offline at once; a driver on
     * a trip finishes it and trip-service's completion event brings them back as OFFLINE.
     */
    public Optional<AvailabilityChange> suspend(String reason, Instant now) {
        if (reviewStatus != ReviewStatus.APPROVED) {
            throw DomainException.invalidState("Only approved drivers can be suspended");
        }
        statusReason = requireReason(reason);
        reviewStatus = ReviewStatus.SUSPENDED;
        updatedAt = now;
        return availability == Availability.AVAILABLE
                ? Optional.of(changeAvailability(Availability.OFFLINE, null, "SUSPENDED", now))
                : Optional.empty();
    }

    public void reinstate(Instant now) {
        if (reviewStatus != ReviewStatus.SUSPENDED) {
            throw DomainException.invalidState("Only suspended drivers can be reinstated");
        }
        reviewStatus = ReviewStatus.APPROVED;
        statusReason = null;
        updatedAt = now;
    }

    // ---- availability -------------------------------------------------------------------

    /**
     * BR-001: only an approved driver with an active account, valid papers and a valid vehicle
     * that fits the requested services may become AVAILABLE.
     *
     * @param workProblems result of {@link DocumentRequirements#workProblems} for {@code vehicle}
     */
    public AvailabilityChange goOnline(boolean accountActive, Vehicle vehicle, Set<ServiceType> requested,
                                       List<String> workProblems, Instant now) {
        if (availability != Availability.OFFLINE) {
            throw DomainException.invalidState("Driver is already " + availability);
        }
        if (!accountActive) {
            throw DomainException.rule("The account is not active");
        }
        if (reviewStatus != ReviewStatus.APPROVED) {
            throw DomainException.rule("The driver profile is " + reviewStatus + ", not APPROVED");
        }
        if (!vehicle.getDriverId().equals(driverId)) {
            throw DomainException.rule("The vehicle does not belong to this driver");
        }
        if (requested.isEmpty() || !serviceTypes.containsAll(requested)) {
            throw DomainException.rule("Requested services are not enabled on the profile");
        }
        if (!requested.stream().allMatch(vehicle.getType()::supports)) {
            throw DomainException.rule("The vehicle cannot serve the requested services");
        }
        requireNoProblems(workProblems);
        onlineServiceTypes = EnumSet.copyOf(requested);
        return changeAvailability(Availability.AVAILABLE, vehicle.getId(), null, now);
    }

    /** Idempotent: going offline when already offline changes nothing. */
    public Optional<AvailabilityChange> goOffline(String reason, Instant now) {
        if (availability == Availability.OFFLINE) {
            return Optional.empty();
        }
        if (availability.hasTripInProgress()) {
            throw DomainException.invalidState("Cannot go offline with an offer or trip in progress");
        }
        return Optional.of(changeAvailability(Availability.OFFLINE, null, reason, now));
    }

    // ---- projected from trip-service (README §4.8) ---------------------------------------
    // dispatch.offers.v1 and trip.events.v1 are not ordered against each other, so each step
    // checks the offer or trip it belongs to; events that do not fit change nothing.

    /**
     * DriverOfferCreated: only an AVAILABLE, approved driver becomes OFFERED, and only for an offer
     * that is still open and is not the one already accepted (it can arrive after TripAccepted,
     * or after the whole trip).
     */
    public Optional<AvailabilityChange> offered(UUID offerId, Instant expiresAt, Instant now) {
        if (availability != Availability.AVAILABLE || reviewStatus != ReviewStatus.APPROVED
                || !expiresAt.isAfter(now) || offerId.equals(acceptedOfferId)) {
            return Optional.empty();
        }
        currentOfferId = offerId;
        currentOfferExpiresAt = expiresAt;
        return Optional.of(changeAvailability(Availability.OFFERED, activeVehicleId, null, now));
    }

    /** DriverOfferExpired / Declined / Cancelled for the offer the driver currently holds. */
    public Optional<AvailabilityChange> offerClosed(UUID offerId, String reason, Instant now) {
        if (availability != Availability.OFFERED || !offerId.equals(currentOfferId)) {
            return Optional.empty();
        }
        clearOffer();
        return Optional.of(backToWork(reason, now));
    }

    /**
     * Safety net for a closing event that never arrives: a driver still OFFERED {@code grace}
     * after the offer ran out is free again. Offers without a recorded expiry count as stale.
     */
    public Optional<AvailabilityChange> releaseStaleOffer(Duration grace, Instant now) {
        if (availability != Availability.OFFERED
                || (currentOfferExpiresAt != null && !currentOfferExpiresAt.plus(grace).isBefore(now))) {
            return Optional.empty();
        }
        clearOffer();
        return Optional.of(backToWork("OFFER_STALE", now));
    }

    /**
     * TripAccepted: the driver is BUSY whatever this service thought before, because the
     * assignment already happened in trip-service. A replay of the same trip changes nothing.
     */
    public Optional<AvailabilityChange> tripAssigned(UUID tripId, UUID offerId, Instant now) {
        if (availability == Availability.BUSY && tripId.equals(currentTripId)) {
            return Optional.empty();
        }
        clearOffer();
        acceptedOfferId = offerId;
        currentTripId = tripId;
        return Optional.of(changeAvailability(Availability.BUSY, activeVehicleId, null, now));
    }

    /** TripCompleted / TripCancelled for the driver's current trip. */
    public Optional<AvailabilityChange> tripEnded(UUID tripId, String reason, Instant now) {
        if (availability != Availability.BUSY || !tripId.equals(currentTripId)) {
            return Optional.empty();
        }
        currentTripId = null;
        return Optional.of(backToWork(reason, now));
    }

    // ---- internals ----------------------------------------------------------------------

    private void clearOffer() {
        currentOfferId = null;
        currentOfferExpiresAt = null;
    }

    /** AVAILABLE again, or OFFLINE if the driver was suspended meanwhile or has no active vehicle. */
    private AvailabilityChange backToWork(String reason, Instant now) {
        boolean canWork = reviewStatus == ReviewStatus.APPROVED && activeVehicleId != null;
        return canWork
                ? changeAvailability(Availability.AVAILABLE, activeVehicleId, reason, now)
                : changeAvailability(Availability.OFFLINE, null, reason, now);
    }

    private AvailabilityChange changeAvailability(Availability target, UUID vehicleId, String reason, Instant now) {
        Availability old = availability;
        UUID vehicle = vehicleId != null ? vehicleId : activeVehicleId;
        availability = target;
        activeVehicleId = target == Availability.OFFLINE ? null : vehicleId;
        availabilityChangedAt = now;
        updatedAt = now;
        // Drivers online before online_service_types existed fall back to the profile's services.
        Set<ServiceType> services = onlineServiceTypes.isEmpty() ? serviceTypes : onlineServiceTypes;
        AvailabilityChange change = new AvailabilityChange(driverId, old, target, Set.copyOf(services), vehicle,
                reason, now);
        if (target == Availability.OFFLINE) {
            // Replaced, not cleared: an in-place change on a converted attribute may go unnoticed.
            onlineServiceTypes = EnumSet.noneOf(ServiceType.class);
        }
        return change;
    }

    private void applyDetails(String fullName, LocalDate dateOfBirth, Set<ServiceType> serviceTypes, Instant now) {
        if (serviceTypes.isEmpty()) {
            throw DomainException.rule("Choose at least one service type");
        }
        if (dateOfBirth != null && dateOfBirth.plusYears(18).isAfter(LocalDate.ofInstant(now, ZoneOffset.UTC))) {
            throw DomainException.rule("Drivers must be at least 18 years old");
        }
        this.fullName = fullName.strip();
        this.dateOfBirth = dateOfBirth;
        this.serviceTypes.clear();
        this.serviceTypes.addAll(serviceTypes);
        this.updatedAt = now;
    }

    private void requireUnderReview(int reviewedVersion) {
        if (reviewStatus != ReviewStatus.PENDING_REVIEW) {
            throw DomainException.invalidState("The profile is " + reviewStatus + ", not PENDING_REVIEW");
        }
        if (reviewedVersion != profileVersion) {
            throw DomainException.invalidState("The profile changed since version " + reviewedVersion
                    + ", review version " + profileVersion);
        }
    }

    private static void requireNoProblems(List<String> problems) {
        if (!problems.isEmpty()) {
            throw DomainException.rule("Profile is incomplete: " + String.join("; ", problems));
        }
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw DomainException.rule("A reason is required");
        }
        return reason.strip();
    }

    public Set<ServiceType> getServiceTypes() {
        return Collections.unmodifiableSet(serviceTypes);
    }
}
