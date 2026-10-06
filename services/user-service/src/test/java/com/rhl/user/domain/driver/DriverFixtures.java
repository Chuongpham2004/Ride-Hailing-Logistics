package com.rhl.user.domain.driver;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

final class DriverFixtures {

    static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
    static final DocumentRequirements REQUIREMENTS = new DocumentRequirements(
            EnumSet.of(DocumentType.NATIONAL_ID, DocumentType.DRIVER_LICENSE),
            EnumSet.of(DocumentType.VEHICLE_REGISTRATION, DocumentType.VEHICLE_INSURANCE));

    private DriverFixtures() {
    }

    static DriverProfile draft(UUID driverId) {
        return DriverProfile.create(driverId, "Nguyen Van A", LocalDate.of(1990, 1, 1),
                EnumSet.of(ServiceType.RIDE, ServiceType.DELIVERY), NOW);
    }

    static Vehicle motorbike(UUID driverId) {
        return Vehicle.register(driverId, VehicleType.MOTORBIKE, "59X3-123.45", "Honda", "Wave", "Red", 2020, NOW);
    }

    static List<DriverDocument> completePapers(UUID driverId, Vehicle vehicle, LocalDate expiresOn) {
        List<DriverDocument> docs = new ArrayList<>();
        docs.add(doc(driverId, null, DocumentType.NATIONAL_ID, null));
        docs.add(doc(driverId, null, DocumentType.DRIVER_LICENSE, expiresOn));
        docs.add(doc(driverId, vehicle.getId(), DocumentType.VEHICLE_REGISTRATION, null));
        docs.add(doc(driverId, vehicle.getId(), DocumentType.VEHICLE_INSURANCE, expiresOn));
        return docs;
    }

    static DriverDocument doc(UUID driverId, UUID vehicleId, DocumentType type, LocalDate expiresOn) {
        return DriverDocument.submit(driverId, vehicleId, type, "NO-" + type.ordinal() + "1234", null, expiresOn,
                null, NOW);
    }

    /** A profile that passed review, with valid papers. */
    static DriverProfile approved(UUID driverId, Vehicle vehicle) {
        DriverProfile profile = draft(driverId);
        List<String> problems = REQUIREMENTS.profileProblems(completePapers(driverId, vehicle, TODAY.plusYears(1)),
                List.of(vehicle), TODAY);
        profile.submit(problems, NOW);
        profile.approve(profile.getProfileVersion(), problems, NOW);
        return profile;
    }
}
