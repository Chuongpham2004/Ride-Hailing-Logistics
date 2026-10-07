package com.rhl.realtime.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TripParticipantsTest {

    private static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");
    private static final Duration GRACE = Duration.ofMinutes(2);
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID DRIVER = UUID.randomUUID();

    @ParameterizedTest
    @ValueSource(strings = {"MATCHING", "ACCEPTED", "PICKING_UP", "ARRIVED", "IN_TRIP"})
    void aRunningTripCanBeFollowed(String status) {
        assertThat(trip(status, null).isFollowable(NOW, GRACE)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "CANCELLED", "NO_DRIVER"})
    void anEndedTripCanBeFollowedOnlyDuringTheGracePeriod(String status) {
        TripParticipants ended = trip(status, NOW.minus(GRACE).plusSeconds(1));
        assertThat(ended.isFollowable(NOW, GRACE)).isTrue();
        assertThat(ended.isFollowable(NOW.plusSeconds(1), GRACE)).isFalse();
        assertThat(trip(status, null).isFollowable(NOW, GRACE)).isFalse();
    }

    @Test
    void onlyTheCustomerAndTheAssignedDriverTakePart() {
        TripParticipants trip = trip("IN_TRIP", null);
        assertThat(trip.participant(CUSTOMER)).contains(TripParticipants.Participant.CUSTOMER);
        assertThat(trip.participant(DRIVER)).contains(TripParticipants.Participant.DRIVER);
        assertThat(trip.participant(UUID.randomUUID())).isEmpty();
        TripParticipants matching = new TripParticipants(UUID.randomUUID(), CUSTOMER, null, "MATCHING", null, 1);
        assertThat(matching.participant(DRIVER)).isEmpty();
    }

    private static TripParticipants trip(String status, Instant endedAt) {
        return new TripParticipants(UUID.randomUUID(), CUSTOMER, DRIVER, status, endedAt, 3);
    }
}
