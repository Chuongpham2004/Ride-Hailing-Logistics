package com.rhl.user.infrastructure.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Fixed-window counters per action and client ({@code rl:user:{action}:{hash}}, NFR-SEC-007).
 * The client (an IP address) is hashed, so Redis holds no addresses.
 */
@Component
@RequiredArgsConstructor
public class ClientRateLimiter {

    private static final String PREFIX = "rl:user:";

    private final StringRedisTemplate redis;

    /** @return {@code false} when {@code client} already used {@code limit} calls in this window */
    public boolean tryAcquire(String action, String client, int limit, Duration window) {
        String key = PREFIX + action + ":" + hash(client);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, window);
        }
        return count != null && count <= limit;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
