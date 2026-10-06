package com.rhl.common.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contract test: the producer's sample event must satisfy the packaged schema (README §4.7). */
class EventSchemaValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final EventSchemaValidator validator = new EventSchemaValidator(mapper);

    @Test
    void loadsSchemasFromContracts() {
        assertThat(validator.hasSchema("DriverAvailabilityChanged", 1)).isTrue();
        assertThat(validator.hasSchema("envelope", 1)).isTrue();
    }

    @Test
    void acceptsTheContractExample() throws IOException {
        JsonNode example = example("DriverAvailabilityChanged.v1");

        assertThatCode(() -> validator.validate("DriverAvailabilityChanged", 1, example)).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownStatusAndMissingEnvelopeFields() throws IOException {
        ObjectNode event = (ObjectNode) example("DriverAvailabilityChanged.v1");
        ((ObjectNode) event.get("payload")).put("newStatus", "SLEEPING");
        event.remove("correlationId");

        assertThatThrownBy(() -> validator.validate("DriverAvailabilityChanged", 1, event))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("correlationId")
                .hasMessageContaining("newStatus");
    }

    @Test
    void enforcesFormats() throws IOException {
        ObjectNode event = (ObjectNode) example("DriverAvailabilityChanged.v1");
        event.put("eventId", "not-a-uuid");

        assertThatThrownBy(() -> validator.validate("DriverAvailabilityChanged", 1, event))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("eventId");
    }

    @Test
    void rejectsEventsWithoutSchema() {
        assertThatThrownBy(() -> validator.validate("Unknown", 1, mapper.createObjectNode()))
                .isInstanceOf(InvalidEventException.class);
    }

    private JsonNode example(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/contracts/events/examples/" + name + ".example.json")) {
            assertThat(in).as("example " + name).isNotNull();
            return mapper.readTree(in);
        }
    }
}
