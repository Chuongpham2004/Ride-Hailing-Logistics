package com.rhl.realtime.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rhl.common.id.UuidV7;
import com.rhl.common.security.Role;
import com.rhl.common.security.RolesJwtAuthenticationConverter;
import com.rhl.common.web.ErrorCode;
import com.rhl.realtime.RealtimeProperties;
import com.rhl.realtime.application.ClientSession;
import com.rhl.realtime.application.CloseCodes;
import com.rhl.realtime.application.Messages;
import com.rhl.realtime.application.SessionRegistry;
import com.rhl.realtime.application.TripChannel;
import com.rhl.realtime.infrastructure.messaging.TelemetryPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The WebSocket endpoint (CON-03, README §8.4). The handshake was authenticated by the security
 * filter chain; this handler binds the session to that user, answers heartbeats (COM-006),
 * accepts fresh tokens (COM-005), forwards driver locations and manages trip subscriptions.
 * Pushes to the client come from {@code EventRouter} and {@code TripChannel}.
 */
@Slf4j
@Component
public class RealtimeWebSocketHandler extends TextWebSocketHandler {

    public static final String PING = "PING";
    public static final String AUTH = "AUTH";
    public static final String DRIVER_LOCATION_UPDATED = "DRIVER_LOCATION_UPDATED";
    public static final String SUBSCRIBE_TRIP = "SUBSCRIBE_TRIP";
    public static final String UNSUBSCRIBE_TRIP = "UNSUBSCRIBE_TRIP";

    private static final String SESSION = "rhl.session";
    private static final int MAX_ERROR_LENGTH = 500;
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final SessionRegistry registry;
    private final Messages messages;
    private final ClientMessageSchemas schemas;
    private final TelemetryPublisher telemetry;
    private final TripChannel trips;
    private final JwtDecoder jwtDecoder;
    private final RealtimeProperties.Realtime config;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RealtimeWebSocketHandler(SessionRegistry registry, Messages messages, ClientMessageSchemas schemas,
                                    TelemetryPublisher telemetry, TripChannel trips, JwtDecoder jwtDecoder,
                                    RealtimeProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.registry = registry;
        this.messages = messages;
        this.schemas = schemas;
        this.telemetry = telemetry;
        this.trips = trips;
        this.jwtDecoder = jwtDecoder;
        this.config = properties.realtime();
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
        if (!(socket.getPrincipal() instanceof JwtAuthenticationToken auth)) {
            socket.close(CloseCodes.TOKEN_INVALID);
            return;
        }
        Jwt jwt = auth.getToken();
        WebSocketSession guarded = new ConcurrentWebSocketSessionDecorator(socket,
                (int) config.sendTimeLimit().toMillis(), config.sendBufferBytes());
        Instant now = clock.instant();
        ClientSession session = new ClientSession(guarded, UUID.fromString(jwt.getSubject()),
                roles(auth.getAuthorities()), jwt.getId(), jwt.getExpiresAt(), now);
        socket.getAttributes().put(SESSION, session);
        registry.register(session);

        ObjectNode data = messages.data();
        data.put("sessionId", session.id());
        data.put("userId", session.getUserId().toString());
        data.set("roles", objectMapper.valueToTree(session.getRoles().stream().map(Role::name).sorted().toList()));
        data.put("tokenExpiresAt", session.getTokenExpiresAt().toString());
        data.put("heartbeatIntervalSeconds", Math.max(1, config.heartbeatTimeout().toSeconds() / 3));
        session.send(messages.create(Messages.SESSION_READY, data), objectMapper);
        log.debug("Session {} opened for user {}", session.id(), session.getUserId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage text) {
        if (!(socket.getAttributes().get(SESSION) instanceof ClientSession session)) {
            return;
        }
        Instant now = clock.instant();
        if (!now.isBefore(session.getTokenExpiresAt())) {
            session.close(CloseCodes.TOKEN_EXPIRED);
            return;
        }
        session.touch(now);
        JsonNode message;
        try {
            message = objectMapper.readTree(text.getPayload());
        } catch (JsonProcessingException e) {
            reply(session, ErrorCode.VALIDATION_ERROR, "Message is not valid JSON", null);
            return;
        }
        if (message == null || !message.isObject()) {
            reply(session, ErrorCode.VALIDATION_ERROR, "Message must be a JSON object", null);
            return;
        }
        String messageId = uuidOrNull(message.path("messageId"));
        try {
            schemas.validate(message);
        } catch (ClientMessageSchemas.InvalidMessageException e) {
            reply(session, ErrorCode.VALIDATION_ERROR, e.getMessage(), messageId);
            return;
        }
        switch (message.path("type").asText()) {
            case PING -> pong(session, messageId);
            case AUTH -> reauthenticate(session, message.path("data").path("accessToken").asText(), messageId);
            case DRIVER_LOCATION_UPDATED -> locationReported(session, message, messageId, now);
            case SUBSCRIBE_TRIP -> trips.subscribe(session, tripId(message), messageId);
            case UNSUBSCRIBE_TRIP -> trips.unsubscribe(session, tripId(message), messageId);
            default -> reply(session, ErrorCode.VALIDATION_ERROR, "Unsupported message type", messageId);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        if (socket.getAttributes().get(SESSION) instanceof ClientSession session) {
            trips.disconnected(session);
        }
        registry.unregister(socket.getId());
        log.debug("Session {} closed: {}", socket.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession socket, Throwable exception) {
        log.debug("Transport error on session {}: {}", socket.getId(), exception.getMessage());
    }

    private void pong(ClientSession session, String messageId) {
        ObjectNode data = messages.data();
        data.put("inReplyTo", messageId);
        session.send(messages.create(Messages.PONG, data), objectMapper);
    }

    /** COM-005: a fresh token of the same user, with at least the roles the session was opened with. */
    private void reauthenticate(ClientSession session, String token, String messageId) {
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(token);
        } catch (JwtException e) {
            reply(session, ErrorCode.AUTHENTICATION_REQUIRED, "Invalid or expired token", messageId);
            return;
        }
        if (!session.getUserId().toString().equals(jwt.getSubject())) {
            reply(session, ErrorCode.ACCESS_DENIED, "The token belongs to another user", messageId);
            return;
        }
        if (!roles(RolesJwtAuthenticationConverter.authorities(jwt)).containsAll(session.getRoles())) {
            reply(session, ErrorCode.ACCESS_DENIED, "The account roles changed; reconnect with the new token",
                    messageId);
            return;
        }
        session.reauthenticated(jwt.getId(), jwt.getExpiresAt());
        ObjectNode data = messages.data();
        data.put("inReplyTo", messageId);
        data.put("tokenExpiresAt", jwt.getExpiresAt().toString());
        session.send(messages.create(Messages.AUTH_REFRESHED, data), objectMapper);
    }

    /** Not acknowledged: reports are frequent and superseded by the next one. */
    private void locationReported(ClientSession session, JsonNode message, String messageId, Instant now) {
        if (!session.has(Role.DRIVER)) {
            reply(session, ErrorCode.ACCESS_DENIED, "Only drivers report their location", messageId);
            return;
        }
        if (!session.acceptTelemetry(now, config.telemetryMinInterval())) {
            reply(session, ErrorCode.RATE_LIMIT_EXCEEDED, "Location reports must be at least "
                    + config.telemetryMinInterval().toMillis() + " ms apart", messageId);
            return;
        }
        String correlationId = message.hasNonNull("correlationId") ? message.path("correlationId").asText()
                : UuidV7.randomString();
        try {
            telemetry.publish(session.getUserId(), message, correlationId, now);
        } catch (RuntimeException e) {
            log.warn("Could not forward location of session {}: {}", session.id(), e.getMessage());
            reply(session, ErrorCode.DEPENDENCY_UNAVAILABLE, "Location could not be forwarded, send the next one",
                    messageId);
        }
    }

    private void reply(ClientSession session, ErrorCode code, String text, String inReplyTo) {
        String message = text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH - 3) + "...";
        session.send(messages.error(code.name(), message, inReplyTo), objectMapper);
    }

    /** Valid by schema (format uuid). */
    private static UUID tripId(JsonNode message) {
        return UUID.fromString(message.path("data").path("tripId").asText());
    }

    private static String uuidOrNull(JsonNode value) {
        return value.isTextual() && UUID_PATTERN.matcher(value.asText()).matches() ? value.asText() : null;
    }

    private static Set<Role> roles(Collection<? extends GrantedAuthority> authorities) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        authorities.forEach(a -> {
            String name = a.getAuthority();
            if (name.startsWith(Role.AUTHORITY_PREFIX)) {
                roles.add(Role.valueOf(name.substring(Role.AUTHORITY_PREFIX.length())));
            }
        });
        return roles;
    }
}
