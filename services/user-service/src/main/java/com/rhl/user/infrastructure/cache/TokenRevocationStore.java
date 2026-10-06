package com.rhl.user.infrastructure.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * {@code auth:revoked:{jti}} (README §4.6): access tokens revoked before expiry. The key lives
 * exactly as long as the token would, so the set never grows unbounded. The gateway reads the
 * same keys.
 */
@Component
@RequiredArgsConstructor
public class TokenRevocationStore {

    private static final String PREFIX = "auth:revoked:";

    private final StringRedisTemplate redis;

    public void revoke(String jti, Duration remainingLifetime) {
        if (!remainingLifetime.isNegative() && !remainingLifetime.isZero()) {
            redis.opsForValue().set(PREFIX + jti, "1", remainingLifetime);
        }
    }

    public boolean isRevoked(String jti) {
        return Boolean.TRUE.equals(redis.hasKey(PREFIX + jti));
    }
}
