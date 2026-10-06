package com.rhl.location.infrastructure.cache;

import com.rhl.location.LocationServiceProperties;
import com.rhl.location.domain.LatestLocation;
import com.rhl.location.domain.ServiceType;
import com.rhl.location.domain.TelemetryReport;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Redis side of location-service (README §4.6): latest position, sequence guard, availability
 * marker and the per-service-type GEO index. Everything here is derived data and can be rebuilt
 * from driver_presence plus the next telemetry reports (DR-GEO-005).
 */
@Component
public class LocationStore {

    public enum ApplyResult {
        /** Sequence not newer than the last one seen: nothing changed. */
        DUPLICATE,
        /** Sequence recorded; the report is history only. */
        RECORDED,
        /** The report is now the driver's latest position. */
        CURRENT
    }

    /** One GEOSEARCH hit, nearest first. */
    public record GeoHit(UUID driverId, double distanceMeters) {
    }

    private static final String ALL_TYPES = Arrays.stream(ServiceType.values())
            .map(Enum::name)
            .collect(Collectors.joining(","));
    private static final int PRUNE_BATCH = 1000;

    private final StringRedisTemplate redis;
    private final LocationServiceProperties.Telemetry config;
    private final RedisScript<Long> updateLocation = script("scripts/update-location.lua");
    private final RedisScript<Long> setAvailability = script("scripts/set-availability.lua");
    private final RedisScript<Long> pruneStale = script("scripts/prune-stale.lua");

    public LocationStore(StringRedisTemplate redis, LocationServiceProperties properties) {
        this.redis = redis;
        this.config = properties.telemetry();
    }

    public static String locationKey(UUID driverId) {
        return "loc:driver:" + driverId;
    }

    public static String sequenceKey(UUID driverId) {
        return "loc:seq:" + driverId;
    }

    public static String availabilityKey(UUID driverId) {
        return "loc:avail:" + driverId;
    }

    public static String geoKey(ServiceType type) {
        return "geo:drivers:" + type;
    }

    public static String lastSeenKey(ServiceType type) {
        return "geo:lastseen:" + type;
    }

    public Optional<LatestLocation> latest(UUID driverId) {
        return parse(driverId, redis.<String, String>opsForHash().entries(locationKey(driverId)));
    }

    /** Latest positions of several drivers in one round trip; drivers without one are left out. */
    public Map<UUID, LatestLocation> latest(Collection<UUID> driverIds) {
        List<UUID> ids = List.copyOf(driverIds);
        List<Object> rows = redis.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings({"unchecked", "rawtypes"})
            public Object execute(RedisOperations operations) throws DataAccessException {
                ids.forEach(id -> operations.opsForHash().entries(locationKey(id)));
                return null;
            }
        });
        Map<UUID, LatestLocation> byDriver = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            @SuppressWarnings("unchecked")
            Map<String, String> row = (Map<String, String>) rows.get(i);
            parse(ids.get(i), row).ifPresent(location -> byDriver.put(location.driverId(), location));
        }
        return byDriver;
    }

    public ApplyResult apply(TelemetryReport report, boolean current, Instant serverTime) {
        UUID driverId = report.driverId();
        Long result = redis.execute(updateLocation,
                List.of(sequenceKey(driverId), locationKey(driverId), availabilityKey(driverId)),
                driverId.toString(),
                Long.toString(report.sequence()),
                current ? "1" : "0",
                Double.toString(report.latitude()),
                Double.toString(report.longitude()),
                Double.toString(report.accuracyMeters()),
                report.headingDegrees() == null ? "" : report.headingDegrees().toString(),
                report.speedMetersPerSecond() == null ? "" : report.speedMetersPerSecond().toString(),
                Long.toString(report.deviceTime().toEpochMilli()),
                Long.toString(serverTime.toEpochMilli()),
                Long.toString(config.locationTtl().toMillis()),
                Long.toString(config.sequenceTtl().toMillis()));
        return switch (result == null ? 0 : result.intValue()) {
            case 2 -> ApplyResult.CURRENT;
            case 1 -> ApplyResult.RECORDED;
            default -> ApplyResult.DUPLICATE;
        };
    }

    /**
     * @param matchable   service types to index the driver under; empty takes the driver out
     * @param newSession  the driver just came online, so the app's sequence numbering restarts
     * @return whether the driver is in the GEO index afterwards
     */
    public boolean setAvailability(UUID driverId, Set<ServiceType> matchable, UUID vehicleId, boolean newSession) {
        String types = matchable.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
        Long indexed = redis.execute(setAvailability,
                List.of(availabilityKey(driverId), locationKey(driverId), sequenceKey(driverId)),
                driverId.toString(), types, vehicleId == null ? "" : vehicleId.toString(), ALL_TYPES,
                newSession ? "1" : "0");
        return indexed != null && indexed == 1;
    }

    /** Nearest indexed drivers first; positions still need a freshness check by the caller. */
    public List<GeoHit> search(ServiceType type, double latitude, double longitude, int radiusMeters, int limit) {
        var results = redis.opsForGeo().search(geoKey(type),
                GeoReference.fromCoordinate(longitude, latitude),
                new Distance(radiusMeters, RedisGeoCommands.DistanceUnit.METERS),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().sortAscending()
                        .limit(limit));
        List<GeoHit> hits = new ArrayList<>();
        if (results != null) {
            for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : results) {
                hits.add(new GeoHit(UUID.fromString(result.getContent().getName()), result.getDistance().getValue()));
            }
        }
        return hits;
    }

    /** Drops members not refreshed since {@code cutoff}; returns how many were removed. */
    public long pruneStale(ServiceType type, Instant cutoff) {
        long removed = 0;
        Long batch;
        do {
            batch = redis.execute(pruneStale, List.of(geoKey(type), lastSeenKey(type)),
                    Long.toString(cutoff.toEpochMilli()), Integer.toString(PRUNE_BATCH));
            removed += batch == null ? 0 : batch;
        } while (batch != null && batch == PRUNE_BATCH);
        return removed;
    }

    private static Optional<LatestLocation> parse(UUID driverId, Map<String, String> row) {
        if (row == null || row.isEmpty() || row.get("lat") == null) {
            return Optional.empty();
        }
        return Optional.of(new LatestLocation(driverId,
                Double.parseDouble(row.get("lat")),
                Double.parseDouble(row.get("lng")),
                Double.parseDouble(row.get("acc")),
                optionalDouble(row.get("heading")),
                optionalDouble(row.get("speed")),
                Long.parseLong(row.get("seq")),
                Instant.ofEpochMilli(Long.parseLong(row.get("deviceTs"))),
                Instant.ofEpochMilli(Long.parseLong(row.get("serverTs")))));
    }

    private static Double optionalDouble(String value) {
        return value == null ? null : Double.valueOf(value);
    }

    private static RedisScript<Long> script(String path) {
        try {
            return RedisScript.of(new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8), Long.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load Redis script " + path, e);
        }
    }
}
