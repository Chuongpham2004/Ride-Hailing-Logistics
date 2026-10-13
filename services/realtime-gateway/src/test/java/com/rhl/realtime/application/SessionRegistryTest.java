package com.rhl.realtime.application;

import com.rhl.common.security.Role;
import com.rhl.realtime.RealtimeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionRegistryTest {

    private static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");
    private static final String INSTANCE = "it-instance";

    /** The session directory as Redis would hold it: key to members. */
    private final Map<String, Set<String>> directory = new ConcurrentHashMap<>();
    private Runnable beforeNextAdd = () -> { };
    private SessionRegistry registry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        SetOperations<String, String> sets = mock(SetOperations.class);
        when(sets.add(anyString(), any(String[].class))).thenAnswer(call -> {
            Runnable interleaved = beforeNextAdd;
            beforeNextAdd = () -> { };
            interleaved.run();
            directory.computeIfAbsent(call.getArgument(0), key -> ConcurrentHashMap.newKeySet())
                    .add(call.getArgument(1));
            return 1L;
        });
        when(sets.remove(anyString(), any(Object[].class))).thenAnswer(call -> {
            directory.computeIfPresent(call.getArgument(0), (key, members) -> {
                members.remove(call.<Object>getArgument(1));
                return members.isEmpty() ? null : members;
            });
            return 1L;
        });
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForSet()).thenReturn(sets);

        registry = new SessionRegistry(redis, new RealtimeProperties(
                new RealtimeProperties.Realtime(INSTANCE, List.of("*"), Duration.ofSeconds(45), Duration.ofSeconds(5),
                        Duration.ofSeconds(90), 65_536, Duration.ofSeconds(10), 524_288, Duration.ofSeconds(1),
                        new RealtimeProperties.Trip(Duration.ofSeconds(120), Duration.ofHours(1), 5)),
                new RealtimeProperties.Kafka(1, (short) 1)));
    }

    @Test
    void theDirectoryListsThisInstanceWhileTheUserIsConnected() {
        UUID user = UUID.randomUUID();
        ClientSession phone = session("phone", user);
        ClientSession tablet = session("tablet", user);
        registry.register(phone);
        registry.register(tablet);

        registry.unregister(phone.id());
        assertThat(directory.get(key(user))).containsExactly(INSTANCE);

        registry.unregister(tablet.id());
        assertThat(directory).doesNotContainKey(key(user));
    }

    /**
     * The sweep reads the connected users and then writes to Redis for each; a session that closes
     * in between must not be put back into the directory by that write.
     */
    @Test
    void aRefreshRacingWithTheLastDisconnectDoesNotBringTheEntryBack() {
        UUID user = UUID.randomUUID();
        ClientSession session = session("phone", user);
        registry.register(session);

        beforeNextAdd = () -> registry.unregister(session.id());
        registry.refreshDirectory();

        assertThat(directory).doesNotContainKey(key(user));
    }

    private static String key(UUID user) {
        return SessionRegistry.DIRECTORY_PREFIX + user;
    }

    private static ClientSession session(String id, UUID user) {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(id);
        return new ClientSession(socket, user, Set.of(Role.CUSTOMER), "jti", NOW.plusSeconds(600), NOW);
    }
}
