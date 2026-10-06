package com.rhl.trip.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The transition table of README §6, including who may make each move (BR-008). */
class TripStateMachineTest {

    @ParameterizedTest
    @CsvSource({
            "CREATED, MATCHING, SYSTEM",
            "CREATED, CANCELLED, CUSTOMER",
            "MATCHING, ACCEPTED, DRIVER",
            "MATCHING, NO_DRIVER, SYSTEM",
            "MATCHING, CANCELLED, CUSTOMER",
            "MATCHING, CANCELLED, STAFF",
            "ACCEPTED, PICKING_UP, DRIVER",
            "ACCEPTED, CANCELLED, DRIVER",
            "PICKING_UP, ARRIVED, DRIVER",
            "PICKING_UP, CANCELLED, CUSTOMER",
            "ARRIVED, IN_TRIP, DRIVER",
            "ARRIVED, CANCELLED, DRIVER",
            "IN_TRIP, COMPLETED, DRIVER",
            "IN_TRIP, CANCELLED, STAFF"})
    void allowsTheDocumentedMoves(TripStatus from, TripStatus to, ActorType actor) {
        assertThat(TripStateMachine.allows(from, to, actor)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            // Wrong actor for an otherwise valid move.
            "MATCHING, ACCEPTED, CUSTOMER",
            "MATCHING, NO_DRIVER, CUSTOMER",
            "MATCHING, CANCELLED, DRIVER",
            "IN_TRIP, COMPLETED, CUSTOMER",
            "IN_TRIP, CANCELLED, CUSTOMER",
            "IN_TRIP, CANCELLED, DRIVER",
            // Skipped or backward steps.
            "ACCEPTED, IN_TRIP, DRIVER",
            "ACCEPTED, COMPLETED, DRIVER",
            "ARRIVED, PICKING_UP, DRIVER",
            "MATCHING, CREATED, SYSTEM"})
    void refusesEverythingElse(TripStatus from, TripStatus to, ActorType actor) {
        assertThat(TripStateMachine.allows(from, to, actor)).isFalse();
        assertThatThrownBy(() -> TripStateMachine.check(from, to, actor))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).kind())
                .isEqualTo(DomainException.Kind.INVALID_TRIP_STATE);
    }

    @ParameterizedTest
    @EnumSource(value = TripStatus.class, names = {"COMPLETED", "CANCELLED", "NO_DRIVER"})
    void terminalStatesAreFinal(TripStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        for (TripStatus to : TripStatus.values()) {
            for (ActorType actor : ActorType.values()) {
                assertThat(TripStateMachine.allows(terminal, to, actor)).isFalse();
            }
        }
    }

    @Test
    void assignedDriverStatusesMatchTheUniqueIndex() {
        EnumSet<TripStatus> assigned = EnumSet.noneOf(TripStatus.class);
        for (TripStatus status : TripStatus.values()) {
            if (status.hasAssignedDriver()) {
                assigned.add(status);
            }
        }
        // Must stay in sync with ux_trips_active_driver in V1__init_trip_schema.sql.
        assertThat(assigned).containsExactlyInAnyOrder(TripStatus.ACCEPTED, TripStatus.PICKING_UP,
                TripStatus.ARRIVED, TripStatus.IN_TRIP);
    }
}
