package com.rhl.trip;

import com.rhl.trip.domain.MatchingPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Business thresholds (NFR-MNT-005); matching values are placeholders until TBD-06 is settled. */
@Validated
@ConfigurationProperties(prefix = "rhl")
public record TripServiceProperties(@Valid @NotNull Matching matching, @Valid @NotNull Location location,
                                    @Valid @NotNull Kafka kafka) {

    /**
     * @param candidateLimit drivers asked from location-service per round
     * @param holdGrace      extra lifetime of the Redis hold beyond the offer timeout
     * @param tickInterval   how often expired offers and waiting trips are processed
     * @param batchSize      offers or trips handled per tick
     */
    public record Matching(@Positive int initialRadiusMeters, @Positive int radiusStepMeters,
                           @Positive int maxRadiusMeters, @NotNull Duration offerTimeout,
                           @NotNull Duration matchingTimeout, @Positive int candidateLimit,
                           @NotNull Duration holdGrace, @NotNull Duration tickInterval, @Positive int batchSize) {

        public MatchingPolicy policy() {
            return new MatchingPolicy(initialRadiusMeters, radiusStepMeters, maxRadiusMeters, offerTimeout,
                    matchingTimeout);
        }

        public Duration holdTtl() {
            return offerTimeout.plus(holdGrace);
        }
    }

    public record Location(@NotBlank String baseUrl, @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {
    }

    public record Kafka(@Min(1) int partitions, @Min(1) short replicas) {
    }
}
