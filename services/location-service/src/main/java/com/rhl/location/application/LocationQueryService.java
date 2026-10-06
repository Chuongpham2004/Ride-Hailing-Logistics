package com.rhl.location.application;

import com.rhl.location.LocationServiceProperties;
import com.rhl.location.domain.Availability;
import com.rhl.location.domain.DriverPresence;
import com.rhl.location.domain.LatestLocation;
import com.rhl.location.domain.ServiceType;
import com.rhl.location.infrastructure.cache.LocationStore;
import com.rhl.location.infrastructure.persistence.DriverPresenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Read side: a driver's live position and the nearby-driver search used by matching (FR-LOC-009/010). */
@Service
@RequiredArgsConstructor
public class LocationQueryService {

    /** One matchable driver; distance and freshness let the caller rank and filter (§5.2). */
    public record NearbyDriver(UUID driverId, double distanceMeters, LatestLocation location,
                               Availability availability, UUID vehicleId, Duration locationAge) {
    }

    /** GEOSEARCH asks for this many times {@code limit}, since stale or busy hits are filtered out afterwards. */
    private static final int OVERFETCH = 3;

    private final LocationStore store;
    private final DriverPresenceRepository presences;
    private final LocationServiceProperties properties;
    private final Clock clock;

    /** The live position, or empty when none was reported within the location TTL. */
    public Optional<LatestLocation> current(UUID driverId) {
        Instant now = clock.instant();
        return store.latest(driverId).filter(location -> isFresh(location, now));
    }

    /**
     * Nearest AVAILABLE drivers for {@code type}, closest first. Each hit is checked again against
     * its latest position and the database projection, because the GEO index is only cleaned
     * periodically (README §4.6, BR-004).
     */
    public List<NearbyDriver> nearby(ServiceType type, double latitude, double longitude, int radiusMeters,
                                     int requestedLimit) {
        // Bounded here as well as in the controller, so the over-fetch below can never overflow.
        int limit = Math.clamp(requestedLimit, 1, properties.nearby().maxLimit());
        int radius = Math.clamp(radiusMeters, 1, properties.nearby().maxRadiusMeters());
        List<LocationStore.GeoHit> hits = store.search(type, latitude, longitude, radius, limit * OVERFETCH);
        if (hits.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = hits.stream().map(LocationStore.GeoHit::driverId).toList();
        Map<UUID, LatestLocation> locations = store.latest(ids);
        Map<UUID, DriverPresence> presenceById = presences.findAll(ids);

        Instant now = clock.instant();
        double maxAccuracy = properties.telemetry().maxAccuracyMeters();
        List<NearbyDriver> drivers = new ArrayList<>();
        for (LocationStore.GeoHit hit : hits) {
            LatestLocation location = locations.get(hit.driverId());
            DriverPresence presence = presenceById.get(hit.driverId());
            if (location == null || !isFresh(location, now) || location.accuracyMeters() > maxAccuracy
                    || presence == null || !presence.matchableServiceTypes().contains(type)) {
                continue;
            }
            drivers.add(new NearbyDriver(hit.driverId(), hit.distanceMeters(), location, presence.availability(),
                    presence.vehicleId(), location.age(now)));
            if (drivers.size() == limit) {
                break;
            }
        }
        return drivers;
    }

    private boolean isFresh(LatestLocation location, Instant now) {
        return location.age(now).compareTo(properties.telemetry().locationTtl()) <= 0;
    }
}
