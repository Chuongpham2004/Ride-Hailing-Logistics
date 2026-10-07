package com.rhl.realtime.application;

import com.rhl.common.id.UuidV7;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;

/**
 * Builds server messages in the envelope of {@code contracts/websocket/message.v1.schema.json}
 * (COM-007); {@link ClientSession#send} adds the sequence number.
 */
@Component
@RequiredArgsConstructor
public class Messages {

    public static final String SESSION_READY = "SESSION_READY";
    public static final String PONG = "PONG";
    public static final String AUTH_REFRESHED = "AUTH_REFRESHED";
    public static final String ERROR = "ERROR";

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ObjectNode create(String type, JsonNode data) {
        return create(UuidV7.randomString(), type, 1, data);
    }

    public ObjectNode create(String messageId, String type, int version, JsonNode data) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("messageId", messageId);
        message.put("type", type);
        message.put("version", version);
        message.put("sentAt", clock.instant().toString());
        message.set("data", data);
        return message;
    }

    /** @param inReplyTo the refused message, {@code null} when it could not be read */
    public ObjectNode error(String code, String text, String inReplyTo) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("code", code);
        data.put("message", text);
        if (inReplyTo != null) {
            data.put("inReplyTo", inReplyTo);
        }
        return create(ERROR, data);
    }

    public ObjectNode data() {
        return objectMapper.createObjectNode();
    }

    public ObjectMapper mapper() {
        return objectMapper;
    }
}
