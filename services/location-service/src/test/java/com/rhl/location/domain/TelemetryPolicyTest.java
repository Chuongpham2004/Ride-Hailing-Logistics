package com.rhl.location.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelemetryPolicyTest {

    private static final UUID DRIVER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");

    private final TelemetryPolicy policy = new TelemetryPolicy(Duration.ofSeconds(30), 50, Duration.ofSeconds(10),
            Duration.ofHours(24), 70);

    @Test
    void freshAccurateReportBecomesCurrent() {
        assertThat(policy.assess(report(10.7769, 106.7009, 8, NOW.minusSeconds(1)), null, NOW))
                .isEqualTo(TelemetryQuality.CURRENT);
    }

    @Test
    void outOfRangeCoordinatesAreRejected() {
        assertRejected(report(91, 106.7, 8, NOW), TelemetryRejectedException.Reason.INVALID_COORDINATES);
        assertRejected(report(10.7, -181, 8, NOW), TelemetryRejectedException.Reason.INVALID_COORDINATES);
        assertRejected(report(Double.NaN, 106.7, 8, NOW), TelemetryRejectedException.Reason.INVALID_COORDINATES);
    }

    @Test
    void negativeAccuracyIsRejected() {
        assertRejected(report(10.7, 106.7, -1, NOW), TelemetryRejectedException.Reason.INVALID_ACCURACY);
    }

    @Test
    void timestampsOutsideTheWindowAreRejected() {
        assertRejected(report(10.7, 106.7, 8, NOW.plusSeconds(11)), TelemetryRejectedException.Reason.FUTURE_TIMESTAMP);
        assertRejected(report(10.7, 106.7, 8, NOW.minus(Duration.ofHours(25))),
                TelemetryRejectedException.Reason.TOO_OLD);
    }

    @Test
    void smallClockSkewIsTolerated() {
        assertThat(policy.assess(report(10.7, 106.7, 8, NOW.plusSeconds(5)), null, NOW))
                .isEqualTo(TelemetryQuality.CURRENT);
    }

    @Test
    void lateReportIsBackfillNotCurrent() {
        assertThat(policy.assess(report(10.7, 106.7, 8, NOW.minusSeconds(120)), null, NOW))
                .isEqualTo(TelemetryQuality.BACKFILL);
    }

    @Test
    void reportNotNewerThanStoredPositionIsOutOfOrder() {
        LatestLocation previous = location(10.7, 106.7, NOW.minusSeconds(2));
        assertThat(policy.assess(report(10.7001, 106.7, 8, NOW.minusSeconds(3)), previous, NOW))
                .isEqualTo(TelemetryQuality.OUT_OF_ORDER);
    }

    @Test
    void inaccurateReportIsNotUsedForMatching() {
        assertThat(policy.assess(report(10.7, 106.7, 80, NOW), null, NOW)).isEqualTo(TelemetryQuality.LOW_ACCURACY);
    }

    @Test
    void impossibleJumpIsSuspicious() {
        // ~11 km in 3 s is ~3,700 m/s.
        LatestLocation previous = location(10.7, 106.7, NOW.minusSeconds(3));
        assertThat(policy.assess(report(10.8, 106.7, 8, NOW), previous, NOW)).isEqualTo(TelemetryQuality.SUSPICIOUS);
    }

    @Test
    void normalDrivingIsNotAJump() {
        // ~40 m in 3 s is ~13 m/s (48 km/h).
        LatestLocation previous = location(10.7, 106.7, NOW.minusSeconds(3));
        assertThat(policy.assess(report(10.70036, 106.7, 8, NOW), previous, NOW))
                .isEqualTo(TelemetryQuality.CURRENT);
    }

    @Test
    void distanceMatchesKnownValue() {
        // One degree of latitude is ~111.2 km.
        assertThat(GeoDistance.meters(10, 106, 11, 106)).isBetween(111_000.0, 111_400.0);
    }

    private void assertRejected(TelemetryReport report, TelemetryRejectedException.Reason reason) {
        assertThatThrownBy(() -> policy.assess(report, null, NOW))
                .isInstanceOfSatisfying(TelemetryRejectedException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }

    private static TelemetryReport report(double lat, double lng, double accuracy, Instant deviceTime) {
        return new TelemetryReport(DRIVER, 1, lat, lng, accuracy, null, null, deviceTime);
    }

    private static LatestLocation location(double lat, double lng, Instant deviceTime) {
        return new LatestLocation(DRIVER, lat, lng, 8, null, null, 0, deviceTime, deviceTime);
    }
}
