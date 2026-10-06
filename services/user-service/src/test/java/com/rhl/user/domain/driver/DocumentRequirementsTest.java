package com.rhl.user.domain.driver;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static com.rhl.user.domain.driver.DriverFixtures.REQUIREMENTS;
import static com.rhl.user.domain.driver.DriverFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentRequirementsTest {

    private final UUID driverId = UUID.randomUUID();
    private final Vehicle vehicle = DriverFixtures.motorbike(driverId);

    @Test
    void completeValidPapersHaveNoProblems() {
        List<DriverDocument> papers = DriverFixtures.completePapers(driverId, vehicle, TODAY.plusDays(1));

        assertThat(REQUIREMENTS.profileProblems(papers, List.of(vehicle), TODAY)).isEmpty();
    }

    @Test
    void reportsMissingPapersAndMissingVehicle() {
        List<String> problems = REQUIREMENTS.profileProblems(List.of(), List.of(), TODAY);

        assertThat(problems).containsExactlyInAnyOrder(
                "DRIVER_LICENSE is missing", "NATIONAL_ID is missing", "At least one active vehicle is required");
    }

    @Test
    void documentExpiringTodayIsAlreadyExpired() {
        List<String> problems = REQUIREMENTS.profileProblems(
                DriverFixtures.completePapers(driverId, vehicle, TODAY), List.of(vehicle), TODAY);

        assertThat(problems).containsExactlyInAnyOrder("DRIVER_LICENSE has expired",
                "VEHICLE_INSURANCE of " + vehicle.getPlateNumber() + " has expired");
    }

    @Test
    void vehiclePapersMustBelongToThatVehicle() {
        Vehicle other = Vehicle.register(driverId, VehicleType.MOTORBIKE, "59X312346", "Yamaha", "Sirius", "Blue",
                2019, DriverFixtures.NOW);
        List<DriverDocument> papers = DriverFixtures.completePapers(driverId, vehicle, TODAY.plusYears(1));

        assertThat(REQUIREMENTS.workProblems(papers, other, TODAY))
                .containsExactlyInAnyOrder("VEHICLE_REGISTRATION of 59X312346 is missing",
                        "VEHICLE_INSURANCE of 59X312346 is missing");
    }

    @Test
    void rejectsMisScopedConfiguration() {
        assertThatThrownBy(() -> new DocumentRequirements(EnumSet.of(DocumentType.VEHICLE_INSURANCE),
                EnumSet.noneOf(DocumentType.class))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void vehicleScopedDocumentNeedsAVehicle() {
        assertThatThrownBy(() -> DriverFixtures.doc(driverId, null, DocumentType.VEHICLE_REGISTRATION, null))
                .hasMessageContaining("must reference a vehicle");
        assertThatThrownBy(() -> DriverFixtures.doc(driverId, vehicle.getId(), DocumentType.NATIONAL_ID, null))
                .hasMessageContaining("cannot reference a vehicle");
    }
}
