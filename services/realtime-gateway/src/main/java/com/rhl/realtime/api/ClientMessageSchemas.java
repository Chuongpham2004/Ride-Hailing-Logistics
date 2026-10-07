package com.rhl.realtime.api;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Checks what clients send against {@code contracts/websocket/client} (packaged on the classpath
 * by common-messaging). Client input is untrusted: a message of an unknown type, with extra
 * fields or out-of-range values is refused before anything acts on it.
 */
@Component
public class ClientMessageSchemas {

    private static final String LOCATION = "classpath*:contracts/websocket/**/*.schema.json";
    private static final String CLIENT_TYPES = "/contracts/websocket/client/";

    private final Map<String, Schema> byType = new HashMap<>();

    public ClientMessageSchemas(ObjectMapper objectMapper) {
        Map<String, byte[]> byId = new HashMap<>();
        Map<String, String> clientTypeToId = new HashMap<>();
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources(LOCATION)) {
                byte[] content;
                try (InputStream in = resource.getInputStream()) {
                    content = in.readAllBytes();
                }
                JsonNode schema = objectMapper.readTree(content);
                String id = schema.path("$id").asString();
                byId.put(id, content);
                if (resource.getURL().toString().contains(CLIENT_TYPES)) {
                    clientTypeToId.put(schema.path("properties").path("type").path("const").asString(), id);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load WebSocket schemas from " + LOCATION, e);
        }
        if (clientTypeToId.isEmpty()) {
            throw new IllegalStateException("No client message schemas found at " + LOCATION);
        }
        Map<String, String> sources = new HashMap<>();
        byId.forEach((id, content) -> sources.put(id, new String(content, StandardCharsets.UTF_8)));
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, builder -> builder
                .schemas(Map.copyOf(sources))
                .schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()));
        clientTypeToId.forEach((type, id) -> byType.put(type, registry.getSchema(SchemaLocation.of(id))));
    }

    public Set<String> types() {
        return Set.copyOf(byType.keySet());
    }

    /** @throws InvalidMessageException when the type is unknown or the message does not match it */
    public void validate(JsonNode message) {
        String type = message.path("type").asString();
        Schema schema = byType.get(type);
        if (schema == null) {
            throw new InvalidMessageException("Unsupported message type '" + abbreviate(type) + "'");
        }
        List<Error> errors = schema.validate(message);
        if (!errors.isEmpty()) {
            throw new InvalidMessageException(errors.stream()
                    .map(Error::toString)
                    .sorted()
                    .collect(Collectors.joining("; ")));
        }
    }

    private static String abbreviate(String value) {
        return value.length() <= 40 ? value : value.substring(0, 40) + "...";
    }

    public static class InvalidMessageException extends RuntimeException {

        InvalidMessageException(String message) {
            super(message);
        }
    }
}
