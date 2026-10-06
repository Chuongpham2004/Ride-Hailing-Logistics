package com.rhl.location;

import com.rhl.location.domain.TelemetryPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Business thresholds (NFR-MNT-005); placeholders until TBD-12 and TBD-14 are settled. */
@Validated
@ConfigurationProperties(prefix = "rhl")
public record LocationServiceProperties(@Valid @NotNull Telemetry telemetry, @Valid @NotNull Nearby nearby,
                                        @Valid @NotNull Kafka kafka) {

    /**
     * @param locationTtl lifetime of the latest location; older positions are never matched
     */
    public record Telemetry(@NotNull Duration locationTtl, @Positive double maxAccuracyMeters,
                            @NotNull Duration maxClockSkew, @NotNull Duration maxReportAge,
                            @Positive double maxSpeedMetersPerSecond, @NotNull Duration sequenceTtl,
                            @Min(1) int historyRetentionDays, @NotNull Duration cleanupInterval) {

        public TelemetryPolicy policy() {
            return new TelemetryPolicy(locationTtl, maxAccuracyMeters, maxClockSkew, maxReportAge,
                    maxSpeedMetersPerSecond);
        }
    }

    public record Nearby(@Positive int defaultRadiusMeters, @Positive int maxRadiusMeters,
                         @Positive int defaultLimit, @Positive int maxLimit) {
    }

    public record Kafka(@Min(1) int partitions, @Min(1) short replicas, @Min(1) int telemetryConcurrency) {
    }
}
