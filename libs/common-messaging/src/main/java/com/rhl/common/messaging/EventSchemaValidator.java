package com.rhl.common.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.resource.InputStreamSource;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
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
    private final JsonSchemaFactory factory;
    private final SchemaValidatorsConfig config;
    private final Map<String, JsonSchema> compiled = new ConcurrentHashMap<>();

    public EventSchemaValidator(ObjectMapper objectMapper) {
        this.schemasById = loadSchemas(objectMapper);
        this.factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012, builder -> builder
                .schemaLoaders(loaders -> loaders.add(iri -> {
                    byte[] content = schemasById.get(iri.toString());
                    return content == null ? null : (InputStreamSource) () -> new ByteArrayInputStream(content);
                })));
        this.config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
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
        JsonSchema schema = compiled.computeIfAbsent(id, key -> factory.getSchema(SchemaLocation.of(key), config));
        Set<ValidationMessage> errors = schema.validate(envelope);
        if (!errors.isEmpty()) {
            throw new InvalidEventException(id + " violated: " + errors.stream()
                    .map(ValidationMessage::getMessage)
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
                if (id == null || !id.isTextual()) {
                    throw new IllegalStateException("Schema without $id: " + resource.getDescription());
                }
                if (byId.put(id.asText(), content) != null) {
                    throw new IllegalStateException("Duplicate schema $id " + id.asText());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load event schemas from " + LOCATION, e);
        }
        return Map.copyOf(byId);
    }
}
