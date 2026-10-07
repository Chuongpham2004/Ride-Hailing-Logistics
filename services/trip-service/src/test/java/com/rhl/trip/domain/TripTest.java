package com.rhl.trip.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TripTest {

    private static final Instant NOW = Instant.parse("2026-10-05T08:30:00Z");
    private static final MatchingPolicy POLICY =
            new MatchingPolicy(2000, 1000, 4000, Duration.ofSeconds(15), Duration.ofSeconds(30));
    private static final Stop PICKUP = new Stop(10.7725, 106.698, "Ben Thanh");
    private static final Stop DROPOFF = new Stop(10.7626, 106.6822, "District 5");

    private static FareSnapshot fare(String surge) {
        return new FareSnapshot(UUID.randomUUID(), 27_000L, "VND", new BigDecimal(surge), 1, 2_764, 452);
    }

    private final UUID customer = UUID.randomUUID();
    private final UUID driver = UUID.randomUUID();

    @Test
    void rideFromRequestToCompletion() {
        Trip trip = matchingTrip();

        assertThat(trip.assign(driver, NOW.plusSeconds(5)).to()).isEqualTo(TripStatus.ACCEPTED);
        assertThat(trip.getAcceptedAt()).isEqualTo(NOW.plusSeconds(5));
        trip.advance(TripStatus.PICKING_UP, driver, null, 5, NOW.plusSeconds(10));
        trip.advance(TripStatus.ARRIVED, driver, null, 5, NOW.plusSeconds(200));
        trip.advance(TripStatus.IN_TRIP, driver, null, 5, NOW.plusSeconds(260));
        Transition done = trip.advance(TripStatus.COMPLETED, driver, null, 5, NOW.plusSeconds(900));

        assertThat(done.from()).isEqualTo(TripStatus.IN_TRIP);
        assertThat(done.actor()).isEqualTo(Actor.driver(driver));
        assertThat(trip.getStatus()).isEqualTo(TripStatus.COMPLETED);
        assertThat(trip.getCompletedAt()).isEqualTo(NOW.plusSeconds(900));
    }

    @Test
    void onlyTheAssignedDriverMovesTheTrip() {
        Trip trip = matchingTrip();
        trip.assign(driver, NOW);

        assertThatThrownBy(() -> trip.advance(TripStatus.PICKING_UP, UUID.randomUUID(), null, 5, NOW))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> trip.cancel(Actor.driver(UUID.randomUUID()), CancelReason.OTHER, null, NOW))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void stepsCannotBeSkipped() {
        Trip trip = matchingTrip();
        trip.assign(driver, NOW);

        assertThatThrownBy(() -> trip.advance(TripStatus.COMPLETED, driver, null, 5, NOW))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("ACCEPTED to COMPLETED");
    }

    @Test
    void customerCannotCancelOnceTheTripStarted() {
        Trip trip = matchingTrip();
        trip.assign(driver, NOW);
        trip.advance(TripStatus.PICKING_UP, driver, null, 5, NOW);
        trip.advance(TripStatus.ARRIVED, driver, null, 5, NOW);
        trip.advance(TripStatus.IN_TRIP, driver, null, 5, NOW);

        assertThatThrownBy(() -> trip.cancel(Actor.customer(customer), CancelReason.CHANGED_MIND, null, NOW))
                .isInstanceOf(DomainException.class);

        Transition byStaff = trip.cancel(Actor.staff(UUID.randomUUID()), CancelReason.SAFETY_CONCERN, " incident ",
                NOW.plusSeconds(1));
        assertThat(byStaff.reason()).isEqualTo("SAFETY_CONCERN");
        assertThat(trip.getCancelNote()).isEqualTo("incident");
        assertThat(trip.getCancelledBy()).isEqualTo(ActorType.STAFF);
    }

    @Test
    void searchWidensStepByStepUpToTheMaximum() {
        Trip trip = matchingTrip();

        assertThat(trip.widenSearch(POLICY, NOW)).isTrue();
        assertThat(trip.getMatchingRadiusMeters()).isEqualTo(3000);
        assertThat(trip.widenSearch(POLICY, NOW)).isTrue();
        assertThat(trip.widenSearch(POLICY, NOW)).isFalse();
        assertThat(trip.getMatchingRadiusMeters()).isEqualTo(4000);
    }

    @Test
    void matchingEndsAtTheDeadline() {
        Trip trip = matchingTrip();

        assertThat(trip.isMatchingOverdue(NOW.plusSeconds(29))).isFalse();
        assertThat(trip.isMatchingOverdue(NOW.plusSeconds(30))).isTrue();
        assertThat(trip.giveUp(NOW.plusSeconds(30)).to()).isEqualTo(TripStatus.NO_DRIVER);
        assertThat(trip.isMatchingOverdue(NOW.plusSeconds(60))).isFalse();
    }

    @Test
    void aSurgedQuoteNeedsTheExactMultiplierConfirmed() {
        FareSnapshot surged = fare("1.50");

        assertThatThrownBy(() -> surged.requireSurgeConsent(null))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("x1.50");
        assertThatThrownBy(() -> surged.requireSurgeConsent(new BigDecimal("1.40")))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("changed");
        surged.requireSurgeConsent(new BigDecimal("1.5"));
    }

    @Test
    void anUnsurgedQuoteNeedsNoConfirmationButRejectsAStaleOne() {
        FareSnapshot normal = fare("1.00");

        normal.requireSurgeConsent(null);
        normal.requireSurgeConsent(new BigDecimal("1.00"));
        assertThatThrownBy(() -> normal.requireSurgeConsent(new BigDecimal("1.30")))
                .isInstanceOf(DomainException.class);
    }

    private Trip matchingTrip() {
        Trip trip = Trip.create(UUID.randomUUID(), customer, ServiceType.RIDE, PICKUP, DROPOFF, fare("1.00"), POLICY,
                null, null, NOW);
        trip.startMatching(NOW);
        return trip;
    }
}
