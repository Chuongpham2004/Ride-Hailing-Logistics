package com.rhl.user.domain.driver;

import com.rhl.user.domain.DomainException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.rhl.user.domain.driver.DriverFixtures.NOW;
import static com.rhl.user.domain.driver.DriverFixtures.REQUIREMENTS;
import static com.rhl.user.domain.driver.DriverFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DriverProfileTest {

    private final UUID driverId = UUID.randomUUID();
    private final Vehicle vehicle = DriverFixtures.motorbike(driverId);

    @Nested
    class Review {

        @Test
        void submitMovesDraftToPendingAndBumpsProfileVersion() {
            DriverProfile profile = DriverFixtures.draft(driverId);

            profile.submit(List.of(), NOW);

            assertThat(profile.getReviewStatus()).isEqualTo(ReviewStatus.PENDING_REVIEW);
            assertThat(profile.getProfileVersion()).isEqualTo(1);
            assertThat(profile.getSubmittedAt()).isEqualTo(NOW);
        }

        @Test
        void incompleteProfileCannotBeSubmitted() {
            DriverProfile profile = DriverFixtures.draft(driverId);

            assertThatThrownBy(() -> profile.submit(List.of("DRIVER_LICENSE is missing"), NOW))
                    .isInstanceOf(DomainException.class)
                    .hasMessageContaining("DRIVER_LICENSE is missing");
            assertThat(profile.getReviewStatus()).isEqualTo(ReviewStatus.DRAFT);
        }

        @Test
        void profileUnderReviewIsReadOnly() {
            DriverProfile profile = DriverFixtures.draft(driverId);
            profile.submit(List.of(), NOW);

            assertThatThrownBy(() -> profile.updateDetails("Other", null, EnumSet.of(ServiceType.RIDE), NOW))
                    .isInstanceOf(DomainException.class)
                    .extracting(e -> ((DomainException) e).kind())
                    .isEqualTo(DomainException.Kind.INVALID_STATE);
        }

        @Test
        void approvalNeedsTheReviewedVersionAndValidPapers() {
            DriverProfile profile = DriverFixtures.draft(driverId);
            profile.submit(List.of(), NOW);

            assertThatThrownBy(() -> profile.approve(0, List.of(), NOW)).isInstanceOf(DomainException.class);
            assertThatThrownBy(() -> profile.approve(1, List.of("DRIVER_LICENSE has expired"), NOW))
                    .isInstanceOf(DomainException.class);

            profile.approve(1, List.of(), NOW);
            assertThat(profile.getReviewStatus()).isEqualTo(ReviewStatus.APPROVED);
        }

        @Test
        void rejectionRequiresAReasonAndAllowsResubmission() {
            DriverProfile profile = DriverFixtures.draft(driverId);
            profile.submit(List.of(), NOW);

            assertThatThrownBy(() -> profile.reject(1, " ", NOW)).isInstanceOf(DomainException.class);
            profile.reject(1, "Blurry licence photo", NOW);

            assertThat(profile.getReviewStatus()).isEqualTo(ReviewStatus.REJECTED);
            assertThat(profile.getStatusReason()).isEqualTo("Blurry licence photo");
            profile.submit(List.of(), NOW);
            assertThat(profile.getProfileVersion()).isEqualTo(2);
        }

        @Test
        void requestChangesReturnsTheProfileToDraft() {
            DriverProfile profile = DriverFixtures.draft(driverId);
            profile.submit(List.of(), NOW);

            profile.requestChanges(1, "Add insurance", NOW);

            assertThat(profile.getReviewStatus()).isEqualTo(ReviewStatus.DRAFT);
        }

        @Test
        void driversMustBeAdults() {
            assertThatThrownBy(() -> DriverProfile.create(driverId, "Kid", TODAY.minusYears(17),
                    EnumSet.of(ServiceType.RIDE), NOW)).isInstanceOf(DomainException.class);
        }
    }

    @Nested
    class Availability {

        @Test
        void approvedDriverWithValidPapersGoesOnline() {
            DriverProfile profile = DriverFixtures.approved(driverId, vehicle);

            AvailabilityChange change = profile.goOnline(true, vehicle, Set.of(ServiceType.RIDE), List.of(), NOW);

            assertThat(change.oldStatus()).isEqualTo(com.rhl.user.domain.driver.Availability.OFFLINE);
            assertThat(change.newStatus()).isEqualTo(com.rhl.user.domain.driver.Availability.AVAILABLE);
            assertThat(change.serviceTypes()).containsExactly(ServiceType.RIDE);
            assertThat(change.vehicleId()).isEqualTo(vehicle.getId());
            assertThat(profile.getActiveVehicleId()).isEqualTo(vehicle.getId());
        }

        @Test
        void unapprovedDriverCannotGoOnline() {
            DriverProfile profile = DriverFixtures.draft(driverId);

            assertThatThrownBy(() -> profile.goOnline(true, vehicle, Set.of(ServiceType.RIDE), List.of(), NOW))
                    .isInstanceOf(DomainException.class)
                    .hasMessageContaining("DRAFT");
        }

        @Test
        void inactiveAccountCannotGoOnline() {
            DriverProfile profile = DriverFixtures.approved(driverId, vehicle);

            assertThatThrownBy(() -> profile.goOnline(false, vehicle, Set.of(ServiceType.RIDE), List.of(), NOW))
                    .isInstanceOf(DomainException.class);
        }

        @Test
        void expiredPapersBlockGoingOnline() {
            DriverProfile profile = DriverFixtures.approved(driverId, vehicle);
            List<String> problems = REQUIREMENTS.workProblems(
                    DriverFixtures.completePapers(driverId, vehicle, TODAY), vehicle, TODAY);

            assertThat(problems).contains("DRIVER_LICENSE has expired");
            assertThatThrownBy(() -> profile.goOnline(true, vehicle, Set.of(ServiceType.RIDE), problems, NOW))
                    .isInstanceOf(DomainException.class);
        }

        @Test
        void vehicleMustBelongToTheDriverAndFitTheService() {
            DriverProfile profile = DriverFixtures.approved(driverId, vehicle);
            Vehicle someoneElses = DriverFixtures.motorbike(UUID.randomUUID());
            Vehicle car = Vehicle.register(driverId, VehicleType.CAR_4_SEAT, "51F12345", "Kia", "Morning", "White",
                    2021, NOW);

            assertThatThrownBy(() -> profile.goOnline(true, someoneElses, Set.of(ServiceType.RIDE), List.of(), NOW))
                    .isInstanceOf(DomainException.class);
            assertThatThrownBy(() -> profile.goOnline(true, car, Set.of(ServiceType.DELIVERY), List.of(), NOW))
                    .isInstanceOf(DomainException.class);
        }

        @Test
        void goingOfflineIsIdempotent() {
            DriverProfile profile = DriverFixtures.approved(driverId, vehicle);
            profile.goOnline(true, vehicle, Set.of(ServiceType.RIDE), List.of(), NOW);

            assertThat(profile.goOffline("DRIVER_REQUEST", NOW)).isPresent();
            assertThat(profile.goOffline("DRIVER_REQUEST", NOW)).isEmpty();
            assertThat(profile.getActiveVehicleId()).isNull();
        }

        @Test
        void suspendingAnAvailableDriverTakesThemOffline() {
            DriverProfile profile = DriverFixtures.approved(driverId, vehicle);
            profile.goOnline(true, vehicle, Set.of(ServiceType.RIDE), List.of(), NOW);

            AvailabilityChange change = profile.suspend("Fraud report", NOW).orElseThrow();

            assertThat(profile.getReviewStatus()).isEqualTo(ReviewStatus.SUSPENDED);
            assertThat(change.newStatus()).isEqualTo(com.rhl.user.domain.driver.Availability.OFFLINE);
            assertThat(change.vehicleId()).isEqualTo(vehicle.getId());
            assertThatThrownBy(() -> profile.goOnline(true, vehicle, Set.of(ServiceType.RIDE), List.of(), NOW))
                    .isInstanceOf(DomainException.class);
        }
    }
}
