package com.rhl.pricing.infrastructure.cache;

import com.rhl.pricing.PricingServiceProperties;
import com.rhl.pricing.domain.ServiceType;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Supply and demand counters per H3 cell (README §4.6). Derived data only: losing Redis means
 * surge reads as unavailable (priced at 1.00) until events fill the counters again.
 *
 * <pre>
 * surge:demand:{serviceType}:{cell}  ZSET tripId → request time     sliding window
 * surge:supply:{serviceType}:{cell}  ZSET driverId → position time  freshness window
 * surge:driver:{driverId}            HASH version, status, services, cell, ts
 * </pre>
 */
@Component
public class SurgeCounters {

    static final String DEMAND = "surge:demand:";
    static final String SUPPLY = "surge:supply:";
    static final String DRIVER = "surge:driver:";

    /** Kept while the driver keeps reporting or changing availability. */
    private static final Duration DRIVER_STATE_TTL = Duration.ofDays(1);

    private final RedisScript<Long> availability = script("scripts/surge-availability.lua", Long.class);
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> count = script("scripts/surge-count.lua", List.class);
    private final RedisScript<Long> location = script("scripts/surge-location.lua", Long.class);
    private final RedisScript<Long> demand = script("scripts/surge-demand.lua", Long.class);

    private final StringRedisTemplate redis;
    private final Duration demandWindow;
    private final Duration supplyFreshness;

    public SurgeCounters(StringRedisTemplate redis, PricingServiceProperties properties) {
        this.redis = redis;
        this.demandWindow = properties.surge().demandWindow();
        this.supplyFreshness = properties.surge().supplyFreshness();
    }

    public record Counts(int demand, int supply) {
    }

    /** One trip request in its pickup cell; idempotent per trip. */
    public void recordDemand(ServiceType serviceType, String cell, UUID tripId, Instant requestedAt) {
        redis.execute(demand, List.of(DEMAND + serviceType + ":" + cell), tripId.toString(),
                Long.toString(requestedAt.toEpochMilli()), Long.toString(demandWindow.toMillis()));
    }

    /** @return whether the event was newer than what is stored and was applied */
    public boolean recordAvailability(UUID driverId, long version, String status,
                                      Collection<ServiceType> serviceTypes) {
        String services = serviceTypes.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
        Long applied = redis.execute(availability, List.of(DRIVER + driverId), driverId.toString(),
                Long.toString(version), status, services, SUPPLY, Long.toString(DRIVER_STATE_TTL.toMillis()));
        return applied != null && applied == 1;
    }

    /** @return whether the position counted (driver AVAILABLE and the report newer than the last one) */
    public boolean recordPosition(UUID driverId, String cell, Instant at) {
        Long applied = redis.execute(location, List.of(DRIVER + driverId), driverId.toString(), cell,
                Long.toString(at.toEpochMilli()), SUPPLY, Long.toString(supplyFreshness.toMillis()),
                Long.toString(DRIVER_STATE_TTL.toMillis()));
        return applied != null && applied == 1;
    }

    /** Demand in the window and fresh supply over {@code cells}, as of {@code now}. */
    public Counts count(ServiceType serviceType, List<String> cells, Instant now) {
        List<String> keys = new ArrayList<>(cells.size() * 2);
        cells.forEach(cell -> keys.add(DEMAND + serviceType + ":" + cell));
        cells.forEach(cell -> keys.add(SUPPLY + serviceType + ":" + cell));
        List<?> result = redis.execute(count, keys, Integer.toString(cells.size()),
                Long.toString(now.minus(demandWindow).toEpochMilli()),
                Long.toString(now.minus(supplyFreshness).toEpochMilli()));
        if (result == null || result.size() != 2) {
            throw new IllegalStateException("Unexpected surge count result");
        }
        return new Counts(((Number) result.get(0)).intValue(), ((Number) result.get(1)).intValue());
    }

    public Duration demandWindow() {
        return demandWindow;
    }

    private static <T> RedisScript<T> script(String path, Class<T> type) {
        try {
            return RedisScript.of(new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8), type);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load " + path, e);
        }
    }
}
