package com.rhl.realtime.application;

import com.rhl.realtime.RealtimeProperties;
import com.rhl.realtime.infrastructure.security.SecurityConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Closes sessions whose token expired or was revoked (COM-005: logout and password changes
 * revoke tokens in Redis) and sessions that stopped sending (COM-006), then refreshes this
 * instance's entries in the session directory.
 */
@Slf4j
@Component
public class SessionSweeper {

    private final SessionRegistry registry;
    private final StringRedisTemplate redis;
    private final RealtimeProperties.Realtime config;
    private final Clock clock;

    public SessionSweeper(SessionRegistry registry, StringRedisTemplate redis, RealtimeProperties properties,
                          Clock clock) {
        this.registry = registry;
        this.redis = redis;
        this.config = properties.realtime();
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${rhl.realtime.sweep-interval}")
    public void sweep() {
        Instant now = clock.instant();
        for (ClientSession session : registry.all()) {
            if (!now.isBefore(session.getTokenExpiresAt())) {
                session.close(CloseCodes.TOKEN_EXPIRED);
            } else if (session.getLastSeen().plus(config.heartbeatTimeout()).isBefore(now)) {
                session.close(CloseCodes.HEARTBEAT_TIMEOUT);
            } else if (revoked(session.getTokenId())) {
                session.close(CloseCodes.TOKEN_REVOKED);
            }
        }
        registry.refreshDirectory();
    }

    private boolean revoked(String tokenId) {
        if (tokenId == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(SecurityConfiguration.REVOKED_PREFIX + tokenId));
        } catch (DataAccessException e) {
            // Checked again on the next sweep; the token's expiry still ends the session.
            log.warn("Revocation check failed: {}", e.getMostSpecificCause().getMessage());
            return false;
        }
    }
}
