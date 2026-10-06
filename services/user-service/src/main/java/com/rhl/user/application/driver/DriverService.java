package com.rhl.user.application.driver;

import com.rhl.common.web.ApiException;
import com.rhl.user.application.AuditLog;
import com.rhl.user.domain.DomainException;
import com.rhl.user.domain.driver.AvailabilityChange;
import com.rhl.user.domain.driver.DocumentRequirements;
import com.rhl.user.domain.driver.DocumentType;
import com.rhl.user.domain.driver.DriverDocument;
import com.rhl.user.domain.driver.DriverProfile;
import com.rhl.user.domain.driver.ServiceType;
import com.rhl.user.domain.driver.Vehicle;
import com.rhl.user.domain.driver.VehicleType;
import com.rhl.user.domain.user.User;
import com.rhl.user.infrastructure.persistence.DriverDocumentRepository;
import com.rhl.user.infrastructure.persistence.DriverProfileRepository;
import com.rhl.user.infrastructure.persistence.UserRepository;
import com.rhl.user.infrastructure.persistence.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Driver self-service: profile, vehicles, documents, submission and going online/offline. The
 * driver ID always comes from the authenticated token, never from the request (FR-IAM-010).
 */
@Service
@RequiredArgsConstructor
public class DriverService {

    private final DriverProfileRepository profiles;
    private final VehicleRepository vehicles;
    private final DriverDocumentRepository documents;
    private final UserRepository users;
    private final DocumentRequirements requirements;
    private final DriverEventPublisher events;
    private final AuditLog audit;
    private final Clock clock;

    public record ProfileCommand(String fullName, LocalDate dateOfBirth, Set<ServiceType> serviceTypes) {
    }

    public record VehicleCommand(VehicleType type, String plateNumber, String brand, String model, String color,
                                 int manufactureYear) {
    }

    public record DocumentCommand(DocumentType type, UUID vehicleId, String documentNumber, LocalDate issuedOn,
                                  LocalDate expiresOn, String fileRef) {
    }

    @Transactional
    public DriverViews.ProfileView createProfile(UUID driverId, ProfileCommand cmd) {
        if (profiles.existsById(driverId)) {
            throw ApiException.conflict("Driver profile already exists");
        }
        DriverProfile profile = DriverProfile.create(driverId, cmd.fullName(), cmd.dateOfBirth(), cmd.serviceTypes(),
                clock.instant());
        profiles.save(profile);
        audit.success(driverId, "DRIVER_PROFILE_CREATED", "DRIVER_PROFILE", driverId, Map.of());
        return view(profile, false);
    }

    @Transactional
    public DriverViews.ProfileView updateProfile(UUID driverId, ProfileCommand cmd) {
        DriverProfile profile = load(driverId);
        profile.updateDetails(cmd.fullName(), cmd.dateOfBirth(), cmd.serviceTypes(), clock.instant());
        audit.success(driverId, "DRIVER_PROFILE_UPDATED", "DRIVER_PROFILE", driverId, Map.of());
        return view(profile, false);
    }

    @Transactional(readOnly = true)
    public DriverViews.ProfileView myProfile(UUID driverId) {
        return view(load(driverId), false);
    }

    @Transactional
    public DriverViews.VehicleView addVehicle(UUID driverId, VehicleCommand cmd) {
        load(driverId).requireEditable();
        Vehicle vehicle = Vehicle.register(driverId, cmd.type(), cmd.plateNumber(), cmd.brand(), cmd.model(),
                cmd.color(), cmd.manufactureYear(), clock.instant());
        if (vehicles.existsByPlateNumberAndStatus(vehicle.getPlateNumber(), Vehicle.Status.ACTIVE)) {
            throw ApiException.conflict("This plate number is already registered");
        }
        try {
            vehicles.saveAndFlush(vehicle);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("This plate number is already registered");
        }
        audit.success(driverId, "VEHICLE_ADDED", "VEHICLE", vehicle.getId(),
                Map.of("type", cmd.type().name(), "plate", vehicle.getPlateNumber()));
        return DriverViews.VehicleView.of(vehicle);
    }

    @Transactional
    public void deactivateVehicle(UUID driverId, UUID vehicleId) {
        DriverProfile profile = load(driverId);
        Vehicle vehicle = vehicles.findByIdAndDriverId(vehicleId, driverId)
                .orElseThrow(() -> ApiException.notFound("Vehicle"));
        if (vehicleId.equals(profile.getActiveVehicleId())) {
            throw DomainException.invalidState("Go offline before deactivating the vehicle in use");
        }
        vehicle.deactivate(clock.instant());
        audit.success(driverId, "VEHICLE_DEACTIVATED", "VEHICLE", vehicleId, Map.of());
    }

    /** Adds a document; an existing one of the same type (and vehicle) is kept as REPLACED history. */
    @Transactional
    public DriverViews.DocumentView submitDocument(UUID driverId, DocumentCommand cmd) {
        load(driverId).requireEditable();
        if (cmd.vehicleId() != null && vehicles.findByIdAndDriverId(cmd.vehicleId(), driverId).isEmpty()) {
            throw ApiException.notFound("Vehicle");
        }
        Instant now = clock.instant();
        DriverDocument document = DriverDocument.submit(driverId, cmd.vehicleId(), cmd.type(), cmd.documentNumber(),
                cmd.issuedOn(), cmd.expiresOn(), cmd.fileRef(), now);
        List<DriverDocument> previous = documents.findByDriverIdAndTypeAndStatus(driverId, cmd.type(),
                        DriverDocument.Status.ACTIVE).stream()
                .filter(d -> Objects.equals(d.getVehicleId(), cmd.vehicleId()))
                .toList();
        previous.forEach(DriverDocument::replace);
        // Flush the REPLACED rows first: Hibernate orders inserts before updates, which would
        // trip the one-active-document unique index.
        documents.saveAllAndFlush(previous);
        documents.save(document);
        audit.success(driverId, "DRIVER_DOCUMENT_SUBMITTED", "DRIVER_DOCUMENT", document.getId(),
                Map.of("type", cmd.type().name(), "replaced", previous.size()));
        return DriverViews.DocumentView.of(document, false);
    }

    @Transactional
    public DriverViews.ProfileView submitForReview(UUID driverId) {
        DriverProfile profile = load(driverId);
        profile.submit(requirements.profileProblems(currentDocuments(driverId),
                vehicles.findByDriverIdOrderByCreatedAt(driverId), today()), clock.instant());
        audit.success(driverId, "DRIVER_PROFILE_SUBMITTED", "DRIVER_PROFILE", driverId,
                Map.of("profileVersion", profile.getProfileVersion()));
        return view(profile, false);
    }

    @Transactional
    public DriverViews.ProfileView goOnline(UUID driverId, UUID vehicleId, Set<ServiceType> serviceTypes) {
        DriverProfile profile = load(driverId);
        User user = users.findById(driverId).orElseThrow(() -> ApiException.notFound("User"));
        Vehicle vehicle = vehicles.findByIdAndDriverId(vehicleId, driverId)
                .orElseThrow(() -> ApiException.notFound("Vehicle"));
        AvailabilityChange change = profile.goOnline(user.isActive(), vehicle, serviceTypes,
                requirements.workProblems(currentDocuments(driverId), vehicle, today()), clock.instant());
        publish(profile, change);
        return view(profile, false);
    }

    @Transactional
    public DriverViews.ProfileView goOffline(UUID driverId) {
        DriverProfile profile = load(driverId);
        profile.goOffline("DRIVER_REQUEST", clock.instant()).ifPresent(change -> publish(profile, change));
        return view(profile, false);
    }

    /** Takes a driver offline for a staff action (account locked, suspension); no-op if not a driver. */
    @Transactional
    public void forceOffline(UUID driverId, String reason) {
        profiles.findById(driverId)
                .flatMap(profile -> profile.goOffline(reason, clock.instant())
                        .map(change -> Map.entry(profile, change)))
                .ifPresent(e -> publish(e.getKey(), e.getValue()));
    }

    void publish(DriverProfile profile, AvailabilityChange change) {
        profiles.saveAndFlush(profile);
        events.availabilityChanged(change, profile.getVersion());
    }

    DriverViews.ProfileView view(DriverProfile profile, boolean revealDocumentNumbers) {
        UUID driverId = profile.getDriverId();
        List<Vehicle> driverVehicles = vehicles.findByDriverIdOrderByCreatedAt(driverId);
        List<DriverDocument> docs = currentDocuments(driverId);
        return new DriverViews.ProfileView(driverId, profile.getFullName(), profile.getDateOfBirth(),
                profile.getServiceTypes(), profile.getReviewStatus(), profile.getStatusReason(),
                profile.getProfileVersion(),
                profile.getSubmittedAt(), profile.getAvailability(), profile.getActiveVehicleId(),
                driverVehicles.stream().map(DriverViews.VehicleView::of).toList(),
                docs.stream().map(d -> DriverViews.DocumentView.of(d, revealDocumentNumbers)).toList(),
                requirements.profileProblems(docs, driverVehicles, today()));
    }

    DriverProfile load(UUID driverId) {
        return profiles.findById(driverId).orElseThrow(() -> ApiException.notFound("Driver profile"));
    }

    private List<DriverDocument> currentDocuments(UUID driverId) {
        return documents.findByDriverIdAndStatus(driverId, DriverDocument.Status.ACTIVE);
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
