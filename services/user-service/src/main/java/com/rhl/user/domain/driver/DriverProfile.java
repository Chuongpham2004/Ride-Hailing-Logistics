package com.rhl.user.domain.driver;

import com.rhl.user.domain.DomainException;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
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
 * Driver profile aggregate: review lifecycle (FR-DRV) and the OFFLINE ↔ AVAILABLE part of
 * availability (README §5.3). The driver ID equals the user ID of the driver account.
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
        return changeAvailability(Availability.AVAILABLE, vehicle.getId(), null, now, requested);
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

    // ---- internals ----------------------------------------------------------------------

    private AvailabilityChange changeAvailability(Availability target, UUID vehicleId, String reason, Instant now) {
        return changeAvailability(target, vehicleId, reason, now, serviceTypes);
    }

    private AvailabilityChange changeAvailability(Availability target, UUID vehicleId, String reason, Instant now,
                                                  Set<ServiceType> services) {
        Availability old = availability;
        UUID vehicle = vehicleId != null ? vehicleId : activeVehicleId;
        availability = target;
        activeVehicleId = target == Availability.OFFLINE ? null : vehicleId;
        availabilityChangedAt = now;
        updatedAt = now;
        return new AvailabilityChange(driverId, old, target, Set.copyOf(services), vehicle, reason, now);
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
