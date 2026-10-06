package com.rhl.trip.infrastructure.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * {@code dispatch:driver-hold:{driverId}} = offerId (README §4.6, §4.8): the fast path that keeps
 * two trips from offering the same driver at once. The database indexes stay the final guard, so
 * a lost or expired hold can never cause a double assignment.
 */
@Component
@RequiredArgsConstructor
public class DriverHoldStore {

    private static final String PREFIX = "dispatch:driver-hold:";

    /** Deletes the hold only if it still belongs to this offer. */
    private static final RedisScript<Long> RELEASE = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    /** {@code SET NX PX}: {@code true} when the driver was free and is now held for {@code offerId}. */
    public boolean tryHold(UUID driverId, UUID offerId, Duration ttl) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(PREFIX + driverId, offerId.toString(), ttl));
    }

    public void release(UUID driverId, UUID offerId) {
        redis.execute(RELEASE, List.of(PREFIX + driverId), offerId.toString());
    }
}
