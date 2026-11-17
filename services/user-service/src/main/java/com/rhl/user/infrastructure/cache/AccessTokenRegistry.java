package com.rhl.user.infrastructure.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * {@code auth:user-tokens:{userId}}: the access tokens still valid for an account, as a sorted
 * set of token IDs scored by expiry. Access tokens are stateless JWTs, so this is what lets a
 * password reset or an administrator sign out every device at once: each ID is put on the
 * {@code auth:revoked:{jti}} list that the gateway and every service already check. Expired IDs
 * are dropped on each new token, and the key itself expires with the last token.
 */
@Component
@RequiredArgsConstructor
public class AccessTokenRegistry {

    private static final String PREFIX = "auth:user-tokens:";

    private final StringRedisTemplate redis;
    private final TokenRevocationStore revocations;

    public void register(UUID userId, String jti, Instant expiresAt, Instant now) {
        String key = PREFIX + userId;
        ZSetOperations<String, String> tokens = redis.opsForZSet();
        tokens.add(key, jti, expiresAt.toEpochMilli());
        tokens.removeRangeByScore(key, Double.NEGATIVE_INFINITY, now.toEpochMilli());
        // The newest token expires last; the key goes with it.
        Duration lifetime = Duration.between(now, expiresAt);
        if (lifetime.isPositive()) {
            redis.expire(key, lifetime);
        }
    }

    /** @return how many still-valid access tokens were revoked */
    public int revokeAll(UUID userId, Instant now) {
        String key = PREFIX + userId;
        Set<ZSetOperations.TypedTuple<String>> live = redis.opsForZSet().rangeByScoreWithScores(key,
                now.toEpochMilli(), Double.POSITIVE_INFINITY);
        int revoked = 0;
        if (live != null) {
            for (ZSetOperations.TypedTuple<String> token : live) {
                if (token.getValue() == null || token.getScore() == null) {
                    continue;
                }
                Instant expiresAt = Instant.ofEpochMilli(token.getScore().longValue());
                revocations.revoke(token.getValue(), Duration.between(now, expiresAt));
                revoked++;
            }
        }
        redis.delete(key);
        return revoked;
    }
}
