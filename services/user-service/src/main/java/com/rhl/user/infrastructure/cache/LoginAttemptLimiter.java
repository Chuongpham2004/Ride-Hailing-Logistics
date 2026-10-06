package com.rhl.user.infrastructure.cache;

import com.rhl.user.UserServiceProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * {@code auth:login-fail:{hash}} counter (FR-IAM-006). Keyed by the hashed normalized identifier,
 * so emails and phone numbers never appear in Redis, and applied whether or not the account
 * exists so the limiter reveals nothing.
 */
@Component
@RequiredArgsConstructor
public class LoginAttemptLimiter {

    private static final String PREFIX = "auth:login-fail:";

    private final StringRedisTemplate redis;
    private final UserServiceProperties properties;

    public boolean isBlocked(String identifier) {
        String count = redis.opsForValue().get(key(identifier));
        return count != null && Long.parseLong(count) >= properties.login().maxFailures();
    }

    public void recordFailure(String identifier) {
        String key = key(identifier);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, properties.login().lockDuration());
        }
    }

    public void reset(String identifier) {
        redis.delete(key(identifier));
    }

    private static String key(String identifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(identifier.getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
