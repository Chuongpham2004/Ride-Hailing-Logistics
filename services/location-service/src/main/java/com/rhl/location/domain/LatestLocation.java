package com.rhl.location.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** The driver's current position as kept in {@code loc:driver:{driverId}} (README §10.1). */
public record LatestLocation(
        UUID driverId,
        double latitude,
        double longitude,
        double accuracyMeters,
        Double headingDegrees,
        Double speedMetersPerSecond,
        long sequence,
        Instant deviceTime,
        Instant serverTime) {

    public static LatestLocation of(TelemetryReport report, Instant serverTime) {
        return new LatestLocation(report.driverId(), report.latitude(), report.longitude(), report.accuracyMeters(),
                report.headingDegrees(), report.speedMetersPerSecond(), report.sequence(), report.deviceTime(),
                serverTime);
    }

    public Duration age(Instant now) {
        return Duration.between(serverTime, now);
    }
}
