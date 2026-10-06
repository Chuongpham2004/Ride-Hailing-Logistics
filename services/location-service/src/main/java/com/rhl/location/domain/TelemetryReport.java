package com.rhl.location.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One position report. {@code driverId} always comes from the authenticated session or from the
 * gateway that authenticated it, never from the client payload (FR-LOC).
 */
public record TelemetryReport(
        UUID driverId,
        long sequence,
        double latitude,
        double longitude,
        double accuracyMeters,
        Double headingDegrees,
        Double speedMetersPerSecond,
        Instant deviceTime) {
}
