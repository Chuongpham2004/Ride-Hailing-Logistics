package com.rhl.realtime.infrastructure.cache;

import com.rhl.common.messaging.EventEnvelope;
import com.rhl.realtime.RealtimeProperties;
import com.rhl.realtime.application.TripParticipants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code ws:trip-participants:{tripId}} (HASH) and {@code ws:driver-trip:{driverId}} (the trip a
 * driver currently serves), rebuilt from {@code trip.events.v1} (README §4.6). Every instance
 * applies every trip event; writes are guarded by the event's aggregateVersion, so duplicates
 * and late events never move a trip backwards. Shared in Redis, so an instance that starts later
 * already knows the running trips.
 */
@Component
public class TripParticipantStore {

    static final String TRIP_PREFIX = "ws:trip-participants:";
    static final String DRIVER_PREFIX = "ws:driver-trip:";
    /** Kept a little past the grace period so the end time is still readable when it runs out. */
    private static final Duration MARGIN = Duration.ofMinutes(1);

    /** KEYS[1] trip hash; ARGV[1] version, ARGV[2] TTL seconds, ARGV[3..] field/value pairs. */
    private static final RedisScript<Long> APPLY = RedisScript.of("""
            local current = tonumber(redis.call('HGET', KEYS[1], 'version') or '-1')
            if tonumber(ARGV[1]) <= current then return 0 end
            redis.call('HSET', KEYS[1], 'version', ARGV[1], unpack(ARGV, 3))
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            return 1
            """, Long.class);

    /** KEYS[1] driver key; ARGV[1] trip ID, ARGV[2] TTL seconds: shorten only if it still points at this trip. */
    private static final RedisScript<Long> RELEASE_DRIVER = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final RealtimeProperties.Trip config;

    public TripParticipantStore(StringRedisTemplate redis, RealtimeProperties properties) {
        this.redis = redis;
        this.config = properties.realtime().trip();
    }

    /** @return whether the event changed what is stored (it was newer than the stored state) */
    public boolean apply(EventEnvelope event) {
        JsonNode p = event.payload();
        String status;
        String endedAt = null;
        switch (event.eventType()) {
            case "TripRequested" -> status = p.path("status").asString("MATCHING");
            case "TripAccepted" -> status = "ACCEPTED";
            case "TripStatusChanged" -> {
                status = p.path("newStatus").asString();
                endedAt = TripParticipants.ENDED.contains(status) ? p.path("occurredAt").asString() : null;
            }
            case "TripCompleted" -> {
                status = "COMPLETED";
                endedAt = p.path("completedAt").asString();
            }
            case "TripCancelled" -> {
                status = "CANCELLED";
                endedAt = p.path("cancelledAt").asString();
            }
            default -> {
                return false;
            }
        }
        String tripId = p.path("tripId").asString();
        String driverId = p.hasNonNull("driverId") ? p.path("driverId").asString() : null;
        Duration ttl = endedAt == null ? config.participantsTtl() : config.grace().plus(MARGIN);

        List<String> args = new ArrayList<>(List.of(Long.toString(event.aggregateVersion()),
                Long.toString(ttl.toSeconds()), "customerId", p.path("customerId").asString(), "status", status));
        if (driverId != null) {
            args.add("driverId");
            args.add(driverId);
        }
        if (endedAt != null) {
            args.add("endedAt");
            args.add(endedAt);
        }
        Long applied = redis.execute(APPLY, List.of(TRIP_PREFIX + tripId), args.toArray());
        if (applied == null || applied == 0) {
            return false;
        }
        if (driverId != null) {
            String driverKey = DRIVER_PREFIX + driverId;
            if (endedAt == null) {
                redis.opsForValue().set(driverKey, tripId, config.participantsTtl());
            } else {
                redis.execute(RELEASE_DRIVER, List.of(driverKey), tripId, Long.toString(ttl.toSeconds()));
            }
        }
        return true;
    }

    public Optional<TripParticipants> find(UUID tripId) {
        Map<Object, Object> hash = redis.opsForHash().entries(TRIP_PREFIX + tripId);
        if (hash.isEmpty() || hash.get("customerId") == null) {
            return Optional.empty();
        }
        Object driver = hash.get("driverId");
        Object ended = hash.get("endedAt");
        return Optional.of(new TripParticipants(tripId, UUID.fromString((String) hash.get("customerId")),
                driver == null ? null : UUID.fromString((String) driver), (String) hash.get("status"),
                ended == null ? null : Instant.parse((String) ended), Long.parseLong((String) hash.get("version"))));
    }

    /** The trip the driver serves now, or served until less than the grace period ago. */
    public Optional<UUID> currentTripOf(UUID driverId) {
        String tripId = redis.opsForValue().get(DRIVER_PREFIX + driverId);
        return tripId == null ? Optional.empty() : Optional.of(UUID.fromString(tripId));
    }
}
