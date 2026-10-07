package com.rhl.common.messaging;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Validates full event envelopes against {@code contracts/events} (JSON Schema 2020-12), which
 * common-messaging packages on the classpath. Schemas are addressed by their
 * {@code $id = urn:rhl:event:<EventType>:v<n>}, so cross-file {@code $ref}s resolve offline.
 */
public class EventSchemaValidator {

    private static final String LOCATION = "classpath*:contracts/events/**/*.schema.json";

    private final Map<String, byte[]> schemasById;
    private final SchemaRegistry registry;
    private final Map<String, Schema> compiled = new ConcurrentHashMap<>();

    public EventSchemaValidator(ObjectMapper objectMapper) {
        this.schemasById = loadSchemas(objectMapper);
        Map<String, String> sources = new HashMap<>();
        schemasById.forEach((id, content) -> sources.put(id, new String(content, StandardCharsets.UTF_8)));
        this.registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, builder -> builder
                .schemas(Map.copyOf(sources))
                .schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()));
    }

    public static String schemaId(String eventType, int eventVersion) {
        return "urn:rhl:event:" + eventType + ":v" + eventVersion;
    }

    public boolean hasSchema(String eventType, int eventVersion) {
        return schemasById.containsKey(schemaId(eventType, eventVersion));
    }

    /** @throws InvalidEventException when there is no schema or the event does not match it */
    public void validate(String eventType, int eventVersion, JsonNode envelope) {
        String id = schemaId(eventType, eventVersion);
        if (!schemasById.containsKey(id)) {
            throw new InvalidEventException("No schema registered for " + id);
        }
        Schema schema = compiled.computeIfAbsent(id, key -> registry.getSchema(SchemaLocation.of(key)));
        List<Error> errors = schema.validate(envelope);
        if (!errors.isEmpty()) {
            throw new InvalidEventException(id + " violated: " + errors.stream()
                    .map(Error::toString)
                    .sorted()
                    .collect(Collectors.joining("; ")));
        }
    }

    private static Map<String, byte[]> loadSchemas(ObjectMapper objectMapper) {
        Map<String, byte[]> byId = new HashMap<>();
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources(LOCATION)) {
                byte[] content;
                try (InputStream in = resource.getInputStream()) {
                    content = in.readAllBytes();
                }
                JsonNode id = objectMapper.readTree(content).get("$id");
                if (id == null || !id.isString()) {
                    throw new IllegalStateException("Schema without $id: " + resource.getDescription());
                }
                if (byId.put(id.asString(), content) != null) {
                    throw new IllegalStateException("Duplicate schema $id " + id.asString());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load event schemas from " + LOCATION, e);
        }
        return Map.copyOf(byId);
    }
}
