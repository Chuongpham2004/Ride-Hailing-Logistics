package com.rhl.user.domain.driver;

import com.rhl.user.domain.DomainException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
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

    /** OFFERED and BUSY projected from trip-service events (README §4.8, §5.3). */
    @Nested
    class TripProjection {

        // The nested Availability test class hides the enum's simple name here.
        private static final com.rhl.user.domain.driver.Availability AVAILABLE =
                com.rhl.user.domain.driver.Availability.AVAILABLE;
        private static final com.rhl.user.domain.driver.Availability OFFERED =
                com.rhl.user.domain.driver.Availability.OFFERED;
        private static final com.rhl.user.domain.driver.Availability BUSY =
                com.rhl.user.domain.driver.Availability.BUSY;
        private static final com.rhl.user.domain.driver.Availability OFFLINE =
                com.rhl.user.domain.driver.Availability.OFFLINE;
        private static final Instant EXPIRES = NOW.plusSeconds(15);
        private static final Duration GRACE = Duration.ofSeconds(30);

        private final UUID offerId = UUID.randomUUID();
        private final UUID tripId = UUID.randomUUID();

        @Test
        void offerThenTripThenBackToTheServicesChosenOnline() {
            DriverProfile profile = online();

            assertThat(profile.offered(offerId, EXPIRES, NOW).orElseThrow().newStatus()).isEqualTo(OFFERED);
            assertThat(profile.tripAssigned(tripId, offerId, NOW).orElseThrow().newStatus()).isEqualTo(BUSY);
            assertThat(profile.getCurrentOfferId()).isNull();
            assertThatThrownBy(() -> profile.goOffline("DRIVER_REQUEST", NOW)).isInstanceOf(DomainException.class);

            AvailabilityChange back = profile.tripEnded(tripId, "TRIP_COMPLETED", NOW).orElseThrow();

            assertThat(back.newStatus()).isEqualTo(AVAILABLE);
            assertThat(back.reason()).isEqualTo("TRIP_COMPLETED");
            // RIDE only, as chosen when going online, although the profile also has DELIVERY.
            assertThat(back.serviceTypes()).containsExactly(ServiceType.RIDE);
            assertThat(back.vehicleId()).isEqualTo(vehicle.getId());
        }

        @Test
        void closingTheHeldOfferFreesTheDriverButOtherOffersDoNot() {
            DriverProfile profile = online();
            profile.offered(offerId, EXPIRES, NOW);

            assertThat(profile.offerClosed(UUID.randomUUID(), "OFFER_EXPIRED", NOW)).isEmpty();
            assertThat(profile.offerClosed(offerId, "OFFER_DECLINED", NOW).orElseThrow().newStatus())
                    .isEqualTo(AVAILABLE);
            assertThat(profile.offerClosed(offerId, "OFFER_DECLINED", NOW)).isEmpty();
        }

        @Test
        void lateOrReplayedEventsChangeNothing() {
            DriverProfile profile = online();
            profile.offered(offerId, EXPIRES, NOW);
            profile.tripAssigned(tripId, offerId, NOW);

            // The two topics are not ordered against each other.
            assertThat(profile.offered(offerId, EXPIRES, NOW)).isEmpty();
            assertThat(profile.offerClosed(offerId, "OFFER_CANCELLED", NOW)).isEmpty();
            assertThat(profile.tripAssigned(tripId, offerId, NOW)).isEmpty();
            assertThat(profile.tripEnded(UUID.randomUUID(), "TRIP_CANCELLED", NOW)).isEmpty();
            assertThat(profile.getAvailability()).isEqualTo(BUSY);
        }

        @Test
        void theAcceptedOffersCreatedEventArrivingAfterTheWholeTripIsIgnored() {
            DriverProfile profile = online();
            // trip.events.v1 consumed before dispatch.offers.v1: accept and completion come first.
            profile.tripAssigned(tripId, offerId, NOW);
            profile.tripEnded(tripId, "TRIP_COMPLETED", NOW);

            assertThat(profile.offered(offerId, EXPIRES, NOW)).isEmpty();
            assertThat(profile.getAvailability()).isEqualTo(AVAILABLE);
        }

        @Test
        void anOfferThatAlreadyExpiredIsIgnored() {
            DriverProfile profile = online();

            assertThat(profile.offered(offerId, NOW.minusSeconds(1), NOW)).isEmpty();
            assertThat(profile.offered(offerId, NOW, NOW)).isEmpty();
        }

        @Test
        void aStaleOfferIsReleasedOnlyAfterTheGracePeriod() {
            DriverProfile profile = online();
            profile.offered(offerId, EXPIRES, NOW);

            assertThat(profile.releaseStaleOffer(GRACE, EXPIRES.plus(GRACE))).isEmpty();
            AvailabilityChange freed = profile.releaseStaleOffer(GRACE, EXPIRES.plus(GRACE).plusSeconds(1))
                    .orElseThrow();

            assertThat(freed.newStatus()).isEqualTo(AVAILABLE);
            assertThat(freed.reason()).isEqualTo("OFFER_STALE");
            assertThat(profile.getCurrentOfferId()).isNull();
            assertThat(profile.releaseStaleOffer(GRACE, EXPIRES.plusSeconds(3600))).isEmpty();
        }

        @Test
        void anAcceptedTripMakesTheDriverBusyEvenIfAnOfferEventWasMissed() {
            DriverProfile profile = online();

            assertThat(profile.tripAssigned(tripId, offerId, NOW).orElseThrow().oldStatus()).isEqualTo(AVAILABLE);
            assertThat(profile.getCurrentTripId()).isEqualTo(tripId);
        }

        @Test
        void offlineOrSuspendedDriversAreNotOffered() {
            DriverProfile offline = DriverFixtures.approved(driverId, vehicle);
            assertThat(offline.offered(offerId, EXPIRES, NOW)).isEmpty();

            DriverProfile suspended = online();
            suspended.suspend("Fraud report", NOW);
            assertThat(suspended.offered(offerId, EXPIRES, NOW)).isEmpty();
        }

        @Test
        void aDriverSuspendedDuringATripGoesOfflineWhenItEnds() {
            DriverProfile profile = online();
            profile.tripAssigned(tripId, offerId, NOW);

            assertThat(profile.suspend("Complaint", NOW)).isEmpty();
            AvailabilityChange end = profile.tripEnded(tripId, "TRIP_COMPLETED", NOW).orElseThrow();

            assertThat(end.newStatus()).isEqualTo(OFFLINE);
            assertThat(profile.getActiveVehicleId()).isNull();
            assertThat(profile.getOnlineServiceTypes()).isEmpty();
        }

        private DriverProfile online() {
            DriverProfile profile = DriverFixtures.approved(driverId, vehicle);
            profile.goOnline(true, vehicle, Set.of(ServiceType.RIDE), List.of(), NOW);
            return profile;
        }
    }
}
