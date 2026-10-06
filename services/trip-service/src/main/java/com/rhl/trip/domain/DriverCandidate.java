package com.rhl.trip.domain;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** An AVAILABLE driver near the pickup, as reported by location-service. */
public record DriverCandidate(UUID driverId, long distanceMeters, long locationAgeMillis) {

    /** Closest first, fresher position on a tie (README §5.2 step 4, ranking version 1). */
    static final Comparator<DriverCandidate> RANKING = Comparator
            .comparingLong(DriverCandidate::distanceMeters)
            .thenComparingLong(DriverCandidate::locationAgeMillis)
            .thenComparing(DriverCandidate::driverId);

    /**
     * Candidates worth an offer, best first: drops drivers already offered this trip and drivers
     * with an active trip (README §5.2 step 3). Drivers held for another offer are skipped later,
     * when the hold is taken.
     */
    public static List<DriverCandidate> rank(Collection<DriverCandidate> candidates, Set<UUID> excluded) {
        return candidates.stream()
                .filter(c -> !excluded.contains(c.driverId()))
                .distinct()
                .sorted(RANKING)
                .toList();
    }
}
