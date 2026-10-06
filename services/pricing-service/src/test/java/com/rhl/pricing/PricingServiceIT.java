package com.rhl.pricing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end against real PostgreSQL and Redis (UC-02, FR-PRI, BR-005, BR-007). */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        // Tokens are injected with spring-security-test, so the JWKS endpoint is never called.
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks.json",
        "rhl.quote.ttl=3s"})
class PricingServiceIT {

    private static final Map<String, Object> BEN_THANH =
            Map.of("latitude", 10.7725, "longitude", 106.6980, "address", "Cho Ben Thanh, Quan 1");
    private static final Map<String, Object> DH_KHTN =
            Map.of("latitude", 10.7626, "longitude", 106.6822, "address", "DH Khoa hoc Tu nhien, Quan 5");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    void customerGetsAPricedQuoteThatOnlyTheyAndTripServiceCanUse() throws Exception {
        UUID customer = UUID.randomUUID();

        JsonNode quote = data(quote(customer, "DELIVERY", BEN_THANH, DH_KHTN).andExpect(status().isCreated()));

        String id = quote.path("id").asText();
        JsonNode b = quote.path("breakdown");
        assertThat(b.path("baseFare").asLong() + b.path("distanceFare").asLong() + b.path("timeFare").asLong()
                + b.path("minimumFareAdjustment").asLong() + b.path("surgeAmount").asLong()
                + b.path("roundingAdjustment").asLong()).isEqualTo(quote.path("total").asLong());
        assertThat(quote.path("total").asLong() % 1_000).isZero();
        assertThat(quote.path("currency").asText()).isEqualTo("VND");
        assertThat(quote.path("ruleVersion").asInt()).isEqualTo(1);
        assertThat(quote.path("surgeMultiplier").decimalValue()).isEqualByComparingTo("1.00");
        assertThat(quote.path("routeSource").asText()).isEqualTo("ESTIMATE");
        assertThat(quote.path("distanceMeters").asInt()).isBetween(2_600, 2_900);
        assertThat(Duration.between(Instant.parse(quote.path("createdAt").asText()),
                Instant.parse(quote.path("expiresAt").asText()))).isEqualTo(Duration.ofSeconds(3));
        assertThat(redisTemplate.hasKey("quote:" + id)).isTrue();

        // Owner and administrators can read it; other customers cannot tell it exists.
        perform(get("/api/v1/quotes/" + id), customerToken(customer)).andExpect(status().isOk());
        perform(get("/api/v1/quotes/" + id), token(UUID.randomUUID(), "ADMINISTRATOR")).andExpect(status().isOk());
        perform(get("/api/v1/quotes/" + id), customerToken(UUID.randomUUID())).andExpect(status().isNotFound());

        // trip-service validation (BR-005): owner + service + validity.
        mvc.perform(validate(id, customer, "DELIVERY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(quote.path("total").asLong()));
        mvc.perform(validate(id, UUID.randomUUID(), "DELIVERY")).andExpect(status().isNotFound());
        mvc.perform(validate(id, customer, "RIDE"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));

        // Expired quotes cannot be used, whether read from Redis or from the database.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> mvc.perform(validate(id, customer, "DELIVERY"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("QUOTE_EXPIRED")));
        await().atMost(Duration.ofSeconds(5)).until(() -> !redisTemplate.hasKey("quote:" + id));
        mvc.perform(validate(id, customer, "DELIVERY")).andExpect(jsonPath("$.code").value("QUOTE_EXPIRED"));
    }

    @Test
    void aNewRuleVersionPricesNewQuotesWithoutTouchingIssuedOnes() throws Exception {
        UUID customer = UUID.randomUUID();
        RequestPostProcessor admin = token(UUID.randomUUID(), "ADMINISTRATOR");
        JsonNode before = data(quote(customer, "RIDE", BEN_THANH, DH_KHTN));
        Instant from = Instant.now().plusSeconds(2);

        // Not retroactive, and only administrators may change prices.
        perform(post("/api/v1/admin/pricing/rules"), admin, rule(Instant.now().minusSeconds(60)))
                .andExpect(status().isUnprocessableEntity());
        perform(post("/api/v1/admin/pricing/rules"), customerToken(customer), rule(from))
                .andExpect(status().isForbidden());

        JsonNode v2 = data(perform(post("/api/v1/admin/pricing/rules"), admin, rule(from))
                .andExpect(status().isCreated()));
        assertThat(v2.path("version").asInt()).isEqualTo(2);

        JsonNode rules = data(perform(get("/api/v1/admin/pricing/rules"), admin));
        JsonNode v1 = find(rules, "RIDE", 1);
        assertThat(Instant.parse(v1.path("effectiveTo").asText())).isEqualTo(Instant.parse(v2.path("effectiveFrom")
                .asText()));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(data(quote(customer, "RIDE", BEN_THANH, DH_KHTN)).path("ruleVersion").asInt())
                        .isEqualTo(2));
        JsonNode after = data(quote(customer, "RIDE", BEN_THANH, DH_KHTN));
        assertThat(after.path("total").asLong()).isGreaterThan(before.path("total").asLong());
        assertThat(after.path("breakdown").path("baseFare").asLong()).isEqualTo(20_000);

        // The earlier quote keeps its rule version and price (BR-007).
        perform(get("/api/v1/quotes/" + before.path("id").asText()), customerToken(customer))
                .andExpect(jsonPath("$.data.ruleVersion").value(1))
                .andExpect(jsonPath("$.data.total").value(before.path("total").asLong()));
    }

    @Test
    void quotesValidateTheirInput() throws Exception {
        UUID customer = UUID.randomUUID();
        quote(customer, "RIDE", BEN_THANH, BEN_THANH)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("too close")));
        quote(customer, "RIDE", Map.of("latitude", 21.0285, "longitude", 105.8542, "address", "Ha Noi"), BEN_THANH)
                .andExpect(status().isUnprocessableEntity());
        quote(customer, "RIDE", Map.of("latitude", 95, "longitude", 106.7, "address", "x"), BEN_THANH)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        perform(post("/api/v1/quotes"), token(UUID.randomUUID(), "DRIVER"),
                Map.of("serviceType", "RIDE", "pickup", BEN_THANH, "dropoff", DH_KHTN))
                .andExpect(status().isForbidden());
    }

    // ---- helpers --------------------------------------------------------------------------------

    private ResultActions quote(UUID customer, String serviceType, Map<String, Object> pickup,
                                Map<String, Object> dropoff) throws Exception {
        return perform(post("/api/v1/quotes"), customerToken(customer),
                Map.of("serviceType", serviceType, "pickup", pickup, "dropoff", dropoff));
    }

    private static Map<String, Object> rule(Instant effectiveFrom) {
        return Map.of("serviceType", "RIDE", "regionCode", "DEFAULT", "baseFare", 20_000, "perKm", 5_000,
                "perMinute", 400, "minimumFare", 25_000, "effectiveFrom", effectiveFrom.toString());
    }

    private static MockHttpServletRequestBuilder validate(String quoteId, UUID customer, String serviceType) {
        return get("/internal/v1/quotes/" + quoteId)
                .param("customerId", customer.toString())
                .param("serviceType", serviceType);
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, RequestPostProcessor token)
            throws Exception {
        return mvc.perform(request.with(token));
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, RequestPostProcessor token, Object body)
            throws Exception {
        return mvc.perform(request.with(token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private static JsonNode find(JsonNode rules, String serviceType, int version) {
        for (JsonNode rule : rules) {
            if (rule.path("serviceType").asText().equals(serviceType) && rule.path("version").asInt() == version) {
                return rule;
            }
        }
        throw new AssertionError("No " + serviceType + " v" + version);
    }

    private static RequestPostProcessor customerToken(UUID id) {
        return token(id, "CUSTOMER");
    }

    private static RequestPostProcessor token(UUID id, String role) {
        return jwt().jwt(j -> j.subject(id.toString())).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
