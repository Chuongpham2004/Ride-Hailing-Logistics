package com.rhl.realtime;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/** Session and transport parameters (NFR-MNT-005). */
@Validated
@ConfigurationProperties(prefix = "rhl")
public record RealtimeProperties(@Valid @NotNull Realtime realtime, @Valid @NotNull Kafka kafka) {

    /**
     * @param instanceId           this instance, in its consumer group and the Redis session registry
     * @param allowedOrigins       origin patterns accepted at the handshake
     * @param heartbeatTimeout     a session silent for this long is closed (COM-006)
     * @param sweepInterval        how often sessions are checked for expiry, silence and revocation
     * @param sessionTtl           lifetime of a {@code ws:session:{userId}} entry between refreshes
     * @param maxMessageBytes      largest text message accepted from a client
     * @param sendTimeLimit        a client this slow to read is disconnected
     * @param sendBufferBytes      bytes queued for a slow client before it is disconnected
     * @param telemetryMinInterval smallest gap between two accepted location reports of a driver
     */
    public record Realtime(@NotBlank String instanceId, @NotEmpty List<String> allowedOrigins,
                           @NotNull Duration heartbeatTimeout, @NotNull Duration sweepInterval,
                           @NotNull Duration sessionTtl, @Min(1024) int maxMessageBytes,
                           @NotNull Duration sendTimeLimit, @Min(1024) int sendBufferBytes,
                           @NotNull Duration telemetryMinInterval) {
    }

    public record Kafka(@Min(1) int partitions, @Min(1) short replicas) {
    }
}
