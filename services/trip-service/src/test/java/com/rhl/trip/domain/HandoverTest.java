package com.rhl.trip.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** README §6: pickup confirmation and proof of delivery; UC-05. */
class HandoverTest {

    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");
    private static final MatchingPolicy POLICY =
            new MatchingPolicy(2000, 1000, 4000, Duration.ofSeconds(15), Duration.ofSeconds(30));
    private static final Stop A = new Stop(10.7725, 106.698, "Ben Thanh");
    private static final Stop B = new Stop(10.7626, 106.6822, "District 5");

    private final UUID driver = UUID.randomUUID();

    @Test
    void theTripStartsOnlyWithThePickupCode() {
        Trip trip = arrived(ServiceType.RIDE, "4821", null);

        assertThatThrownBy(() -> trip.advance(TripStatus.IN_TRIP, driver, null, 3, NOW))
                .isInstanceOf(DomainException.class).hasMessageContaining("required");
        assertThatThrownBy(() -> trip.advance(TripStatus.IN_TRIP, driver, "0000", 3, NOW))
                .isInstanceOf(DomainException.class).hasMessageContaining("wrong");
        assertThat(trip.getPickupCodeFailures()).isEqualTo(1);
        assertThat(trip.getStatus()).isEqualTo(TripStatus.ARRIVED);

        trip.advance(TripStatus.IN_TRIP, driver, " 4821 ", 3, NOW);
        assertThat(trip.getStatus()).isEqualTo(TripStatus.IN_TRIP);
    }

    @Test
    void tooManyWrongCodesLockTheCodeEvenTheRightOne() {
        Trip trip = arrived(ServiceType.RIDE, "4821", null);
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> trip.advance(TripStatus.IN_TRIP, driver, "1111", 3, NOW))
                    .hasMessageContaining("wrong");
        }
        assertThatThrownBy(() -> trip.advance(TripStatus.IN_TRIP, driver, "4821", 3, NOW))
                .hasMessageContaining("contact support");
        assertThat(trip.getPickupCodeFailures()).isEqualTo(3);
    }

    @Test
    void codesAreOnlyCheckedInTheRightStateAndForTheAssignedDriver() {
        Trip trip = arrived(ServiceType.RIDE, "4821", null);
        // Completing straight from ARRIVED is not a move: nothing is counted.
        assertThatThrownBy(() -> trip.advance(TripStatus.COMPLETED, driver, "0000", 3, NOW))
                .isInstanceOf(DomainException.class).hasMessageContaining("Cannot move");
        assertThatThrownBy(() -> trip.advance(TripStatus.IN_TRIP, UUID.randomUUID(), "0000", 3, NOW))
                .isInstanceOf(DomainException.class);
        assertThat(trip.getPickupCodeFailures()).isZero();
    }

    @Test
    void withoutAPickupCodeTheDriverStartsDirectly() {
        Trip trip = arrived(ServiceType.RIDE, null, null);
        trip.advance(TripStatus.IN_TRIP, driver, null, 3, NOW);
        assertThat(trip.getStatus()).isEqualTo(TripStatus.IN_TRIP);
    }

    @Test
    void aDeliveryCompletesOnlyWithTheDeliveryCode() {
        Trip trip = arrived(ServiceType.DELIVERY, "1234", "9876");
        trip.advance(TripStatus.IN_TRIP, driver, "1234", 3, NOW);

        assertThatThrownBy(() -> trip.advance(TripStatus.COMPLETED, driver, null, 3, NOW))
                .hasMessageContaining("delivery code is required");
        assertThatThrownBy(() -> trip.advance(TripStatus.COMPLETED, driver, "1234", 3, NOW))
                .hasMessageContaining("delivery code is wrong");
        assertThat(trip.getDeliveryCodeFailures()).isEqualTo(1);

        trip.advance(TripStatus.COMPLETED, driver, "9876", 3, NOW);
        assertThat(trip.getStatus()).isEqualTo(TripStatus.COMPLETED);
    }

    @Test
    void onlyDeliveriesCarryADeliveryCode() {
        assertThatThrownBy(() -> trip(ServiceType.RIDE, null, "1234")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> trip(ServiceType.DELIVERY, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void codesAreNumericAndOfTheConfiguredLength() {
        assertThat(HandoverCodes.generate(4)).matches("^[0-9]{4}$");
        assertThat(HandoverCodes.generate(6)).matches("^[0-9]{6}$");
        assertThatThrownBy(() -> HandoverCodes.generate(3)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deliveryDetailsAreCheckedAndNormalised() {
        UUID trip = UUID.randomUUID();
        DeliveryDetails details = DeliveryDetails.of(trip, " Tran Thi Nhan ", "+84 912.345-678", "Documents",
                PackageSize.SMALL, 500, "  ", 20_000, NOW);

        assertThat(details.getRecipientName()).isEqualTo("Tran Thi Nhan");
        assertThat(details.getRecipientPhone()).isEqualTo("+84912345678");
        assertThat(details.getInstructions()).isNull();
        assertThatThrownBy(() -> DeliveryDetails.of(trip, "A", "123", "x", PackageSize.SMALL, 500, null, 20_000,
                NOW)).hasMessageContaining("phone");
        assertThatThrownBy(() -> DeliveryDetails.of(trip, "A", "0912345678", "x", PackageSize.LARGE, 20_001, null,
                20_000, NOW)).hasMessageContaining("weigh");
    }

    private Trip arrived(ServiceType service, String pickupCode, String deliveryCode) {
        Trip trip = trip(service, pickupCode, deliveryCode);
        trip.startMatching(NOW);
        trip.assign(driver, NOW);
        trip.advance(TripStatus.PICKING_UP, driver, null, 3, NOW);
        trip.advance(TripStatus.ARRIVED, driver, null, 3, NOW);
        return trip;
    }

    private static Trip trip(ServiceType service, String pickupCode, String deliveryCode) {
        return Trip.create(UUID.randomUUID(), UUID.randomUUID(), service, A, B,
                new FareSnapshot(UUID.randomUUID(), 27_000L, "VND", BigDecimal.ONE, 1, 2_764, 452), POLICY,
                pickupCode, deliveryCode, NOW);
    }
}
