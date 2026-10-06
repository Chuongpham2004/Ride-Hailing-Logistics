package com.rhl.location.domain;

import java.time.Duration;
import java.time.Instant;

import static com.rhl.location.domain.TelemetryRejectedException.Reason.FUTURE_TIMESTAMP;
import static com.rhl.location.domain.TelemetryRejectedException.Reason.INVALID_ACCURACY;
import static com.rhl.location.domain.TelemetryRejectedException.Reason.INVALID_COORDINATES;
import static com.rhl.location.domain.TelemetryRejectedException.Reason.TOO_OLD;

/**
 * Validation rules for one report (FR-LOC): range checks, time window, accuracy threshold and
 * impossible jumps. Duplicate and older sequences are dropped atomically in Redis, not here.
 *
 * @param freshFor          a report older than this is history, not the current position
 * @param maxAccuracyMeters worse accuracy is kept but never used for matching (BR-004)
 * @param maxClockSkew      how far in the future a device clock may be
 * @param maxReportAge      older reports are refused outright
 * @param maxSpeed          meters per second above which a move counts as a jump
 */
public record TelemetryPolicy(Duration freshFor, double maxAccuracyMeters, Duration maxClockSkew,
                              Duration maxReportAge, double maxSpeed) {

    /**
     * @param previous the stored latest location, or {@code null} when there is none (expired)
     * @throws TelemetryRejectedException when the report must not be stored
     */
    public TelemetryQuality assess(TelemetryReport report, LatestLocation previous, Instant now) {
        if (!Double.isFinite(report.latitude()) || !Double.isFinite(report.longitude())
                || report.latitude() < -90 || report.latitude() > 90
                || report.longitude() < -180 || report.longitude() > 180) {
            throw new TelemetryRejectedException(INVALID_COORDINATES, "Latitude or longitude is out of range");
        }
        if (!Double.isFinite(report.accuracyMeters()) || report.accuracyMeters() < 0) {
            throw new TelemetryRejectedException(INVALID_ACCURACY, "Accuracy must be zero or positive");
        }
        if (report.deviceTime().isAfter(now.plus(maxClockSkew))) {
            throw new TelemetryRejectedException(FUTURE_TIMESTAMP, "Device timestamp is in the future");
        }
        if (report.deviceTime().isBefore(now.minus(maxReportAge))) {
            throw new TelemetryRejectedException(TOO_OLD, "Device timestamp is too old");
        }

        if (report.deviceTime().isBefore(now.minus(freshFor))) {
            return TelemetryQuality.BACKFILL;
        }
        if (previous != null && !report.deviceTime().isAfter(previous.deviceTime())) {
            return TelemetryQuality.OUT_OF_ORDER;
        }
        if (report.accuracyMeters() > maxAccuracyMeters) {
            return TelemetryQuality.LOW_ACCURACY;
        }
        if (previous != null && isImpossibleJump(previous, report)) {
            return TelemetryQuality.SUSPICIOUS;
        }
        return TelemetryQuality.CURRENT;
    }

    private boolean isImpossibleJump(LatestLocation previous, TelemetryReport report) {
        double meters = GeoDistance.meters(previous.latitude(), previous.longitude(),
                report.latitude(), report.longitude());
        // At least one second, so two reports stamped in the same second are not divided by zero.
        // Both positions' accuracy radius is allowed on top of the speed limit to absorb GPS noise.
        double seconds = Math.max(1.0,
                Duration.between(previous.deviceTime(), report.deviceTime()).toMillis() / 1000.0);
        double tolerance = previous.accuracyMeters() + report.accuracyMeters();
        return meters - tolerance > maxSpeed * seconds;
    }
}
