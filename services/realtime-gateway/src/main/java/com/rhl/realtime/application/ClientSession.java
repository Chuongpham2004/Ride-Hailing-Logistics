package com.rhl.realtime.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rhl.common.security.Role;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One authenticated WebSocket connection. Messages to the client are numbered in the order they
 * are written (COM-007 {@code sequence}); a single lock covers numbering and writing, so the
 * client sees sequence numbers without reordering. The socket is expected to be wrapped in a
 * {@code ConcurrentWebSocketSessionDecorator}, which disconnects clients too slow to read.
 */
@Slf4j
@Getter
public final class ClientSession {

    private final WebSocketSession socket;
    private final UUID userId;
    private final Set<Role> roles;
    private volatile String tokenId;
    private volatile Instant tokenExpiresAt;
    private volatile Instant lastSeen;
    private volatile Instant lastTelemetryAt;
    private long sequence;
    @Getter(AccessLevel.NONE)
    private final Set<UUID> followedTrips = ConcurrentHashMap.newKeySet();

    public ClientSession(WebSocketSession socket, UUID userId, Set<Role> roles, String tokenId,
                         Instant tokenExpiresAt, Instant now) {
        this.socket = Objects.requireNonNull(socket);
        this.userId = Objects.requireNonNull(userId);
        this.roles = Set.copyOf(roles);
        this.tokenId = tokenId;
        this.tokenExpiresAt = Objects.requireNonNull(tokenExpiresAt);
        this.lastSeen = now;
    }

    public String id() {
        return socket.getId();
    }

    public boolean has(Role role) {
        return roles.contains(role);
    }

    /** Any client message proves the connection is alive (COM-006). */
    public void touch(Instant now) {
        lastSeen = now;
    }

    /** Trips this connection follows; managed by {@link TripChannel}. */
    public Set<UUID> followedTrips() {
        return Set.copyOf(followedTrips);
    }

    void follow(UUID tripId) {
        followedTrips.add(tripId);
    }

    void unfollow(UUID tripId) {
        followedTrips.remove(tripId);
    }

    public void reauthenticated(String newTokenId, Instant expiresAt) {
        tokenId = newTokenId;
        tokenExpiresAt = expiresAt;
    }

    /**
     * Rate limit for location reports: accepts one per {@code minInterval}.
     *
     * @return {@code false} when the previous accepted report is too recent
     */
    public synchronized boolean acceptTelemetry(Instant now, Duration minInterval) {
        if (lastTelemetryAt != null && now.isBefore(lastTelemetryAt.plus(minInterval))) {
            return false;
        }
        lastTelemetryAt = now;
        return true;
    }

    /**
     * Numbers and writes one message.
     *
     * @param envelope every field but {@code sequence}, which is set here
     * @return {@code false} when the connection is closed or the write failed
     */
    public boolean send(ObjectNode envelope, ObjectMapper objectMapper) {
        synchronized (this) {
            if (!socket.isOpen()) {
                return false;
            }
            envelope.put("sequence", ++sequence);
            try {
                socket.sendMessage(new TextMessage(objectMapper.writeValueAsString(envelope)));
                return true;
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Cannot serialize message", e);
            } catch (IOException | IllegalStateException e) {
                log.debug("Could not send to session {}: {}", id(), e.getMessage());
                close(CloseStatus.SESSION_NOT_RELIABLE);
                return false;
            }
        }
    }

    public void close(CloseStatus status) {
        try {
            socket.close(status);
        } catch (IOException e) {
            log.debug("Closing session {} failed: {}", id(), e.getMessage());
        }
    }
}
