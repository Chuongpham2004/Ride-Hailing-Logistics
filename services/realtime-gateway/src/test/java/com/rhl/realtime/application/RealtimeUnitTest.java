package com.rhl.realtime.application;

import com.rhl.common.security.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RealtimeUnitTest {

    private static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");

    @ParameterizedTest
    @CsvSource({
            "DriverOfferCreated, DRIVER_OFFER_CREATED",
            "TripStatusChanged, TRIP_STATUS_CHANGED",
            "RefundCompleted, REFUND_COMPLETED",
            "DriverEarningPosted, DRIVER_EARNING_POSTED"})
    void eventTypesBecomeMessageTypes(String eventType, String messageType) {
        assertThat(EventRouter.upperSnake(eventType)).isEqualTo(messageType);
        assertThat(EventRouter.isForwarded(eventType)).isTrue();
    }

    @Test
    void internalEventsAreNotForwarded() {
        assertThat(EventRouter.isForwarded("FareFinalized")).isFalse();
        assertThat(EventRouter.isForwarded("DriverAvailabilityChanged")).isFalse();
    }

    @Test
    void locationReportsAreAcceptedOncePerInterval() {
        ClientSession session = new ClientSession(mock(WebSocketSession.class), UUID.randomUUID(), Set.of(Role.DRIVER),
                "jti", NOW.plusSeconds(600), NOW);
        Duration interval = Duration.ofSeconds(1);

        assertThat(session.acceptTelemetry(NOW, interval)).isTrue();
        assertThat(session.acceptTelemetry(NOW.plusMillis(999), interval)).isFalse();
        assertThat(session.acceptTelemetry(NOW.plusMillis(1_000), interval)).isTrue();
    }
}
