package com.rhl.realtime.application;

import com.rhl.realtime.RealtimeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sessions connected to this instance, by user (a user may have several devices), and the shared
 * directory {@code ws:session:{userId}} = instance IDs that hold a session of that user
 * (README §4.6). Entries expire unless refreshed, so a crashed instance disappears from the
 * directory on its own. Redis being down never refuses a connection: the directory is advisory.
 */
@Slf4j
@Component
public class SessionRegistry {

    static final String DIRECTORY_PREFIX = "ws:session:";

    private final Map<String, ClientSession> byId = new ConcurrentHashMap<>();
    private final Map<UUID, Set<ClientSession>> byUser = new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;
    private final RealtimeProperties.Realtime config;

    public SessionRegistry(StringRedisTemplate redis, RealtimeProperties properties) {
        this.redis = redis;
        this.config = properties.realtime();
    }

    public void register(ClientSession session) {
        byId.put(session.id(), session);
        byUser.computeIfAbsent(session.getUserId(), id -> ConcurrentHashMap.newKeySet()).add(session);
        announce(session.getUserId());
    }

    public void unregister(String sessionId) {
        ClientSession session = byId.remove(sessionId);
        if (session == null) {
            return;
        }
        UUID userId = session.getUserId();
        boolean last = byUser.computeIfPresent(userId, (id, sessions) -> {
            sessions.remove(session);
            return sessions.isEmpty() ? null : sessions;
        }) == null;
        if (last) {
            try {
                redis.opsForSet().remove(DIRECTORY_PREFIX + userId, config.instanceId());
            } catch (DataAccessException e) {
                log.warn("Could not remove session directory entry: {}", e.getMostSpecificCause().getMessage());
            }
        }
    }

    public Collection<ClientSession> forUser(UUID userId) {
        Set<ClientSession> sessions = byUser.get(userId);
        return sessions == null ? List.of() : List.copyOf(sessions);
    }

    public Collection<ClientSession> all() {
        return List.copyOf(byId.values());
    }

    public int size() {
        return byId.size();
    }

    /** Keeps this instance in the directory of every connected user. */
    public void refreshDirectory() {
        for (UUID userId : byUser.keySet()) {
            announce(userId);
        }
    }

    private void announce(UUID userId) {
        String key = DIRECTORY_PREFIX + userId;
        try {
            redis.opsForSet().add(key, config.instanceId());
            redis.expire(key, config.sessionTtl());
        } catch (DataAccessException e) {
            log.warn("Could not update session directory: {}", e.getMostSpecificCause().getMessage());
        }
    }
}
