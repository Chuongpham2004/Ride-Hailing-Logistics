package com.rhl.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rhl.common.id.UuidV7;
import com.rhl.payment.application.PaymentProvider;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end against real PostgreSQL, Redis and Kafka (README §4.9, FR-PAY, FR-WAL, BR-009…012, BR-015). */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks.json",
        "rhl.charge.resolve-after=1s",
        "rhl.charge.resolve-every=300ms",
        "rhl.outbox.poll-interval=50ms"})
class PaymentServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1");

    /** Provider behaviour per test; the real sandbox approves everything. */
    @MockitoBean
    PaymentProvider provider;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void sandbox() {
        when(provider.name()).thenReturn("SANDBOX");
        when(provider.charge(any())).thenAnswer(call ->
                PaymentProvider.ChargeResult.success("sbx_" + ((PaymentProvider.ChargeRequest) call.getArgument(0))
                        .idempotencyKey().replace(":", "_")));
    }

    @Test
    void aFinalFareIsChargedOnceAndTheDriverIsCreditedNetOfCommission() throws Exception {
        UUID trip = UuidV7.random();
        UUID customer = UUID.randomUUID();
        UUID driver = UUID.randomUUID();
        Map<String, Object> fare = fareFinalized(trip, customer, driver, 27_000);

        // Redelivered (same eventId) and duplicated (new eventId, same trip): one payment, one credit.
        UUID eventId = UuidV7.random();
        publish(trip, eventId, "FareFinalized", fare);
        publish(trip, eventId, "FareFinalized", fare);
        publish(trip, UuidV7.random(), "FareFinalized", fare);

        await().ignoreExceptions().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT status FROM payments WHERE trip_id = ?", String.class, trip)).isEqualTo("SUCCEEDED"));
        assertThat(count("SELECT COUNT(*) FROM payments WHERE trip_id = ?", trip)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM payment_attempts a JOIN payments p ON p.id = a.payment_id "
                + "WHERE p.trip_id = ?", trip)).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                        SELECT e.amount FROM wallet_entries e JOIN wallets w ON w.id = e.wallet_id
                        WHERE w.driver_id = ? ORDER BY e.created_at, e.id
                        """, Long.class, driver)).containsExactly(27_000L, -5_400L);
        assertThat(jdbc.queryForObject("SELECT balance FROM wallets WHERE driver_id = ?", Long.class, driver))
                .isEqualTo(21_600);

        JsonNode succeeded = consume("payment.events.v1", trip.toString());
        assertThat(succeeded.path("eventType").asText()).isEqualTo("PaymentSucceeded");
        assertThat(succeeded.path("payload").path("amount").asLong()).isEqualTo(27_000);
        JsonNode earning = consume("wallet.events.v1", driver.toString());
        assertThat(earning.path("eventType").asText()).isEqualTo("DriverEarningPosted");
        assertThat(earning.path("payload").path("netEarning").asLong()).isEqualTo(21_600);
        assertThat(earning.path("payload").path("commission").asLong()).isEqualTo(5_400);
        assertThat(earning.path("payload").path("balance").asLong()).isEqualTo(21_600);

        // The customer sees it, other customers do not, finance staff see everything.
        mvc.perform(get("/api/v1/payments").param("tripId", trip.toString()).with(token(customer, "CUSTOMER")))
                .andExpect(jsonPath("$.data[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[0].amount").value(27_000));
        String paymentId = data(mvc.perform(get("/api/v1/payments").param("tripId", trip.toString())
                .with(token(customer, "CUSTOMER")))).path(0).path("id").asText();
        mvc.perform(get("/api/v1/payments/" + paymentId).with(token(UUID.randomUUID(), "CUSTOMER")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/payments/" + paymentId).with(token(UUID.randomUUID(), "FINANCE_STAFF")))
                .andExpect(status().isOk());

        // The driver's wallet: balance and ledger, newest first.
        mvc.perform(get("/api/v1/wallets/me").with(token(driver, "DRIVER")))
                .andExpect(jsonPath("$.data.balance").value(21_600))
                .andExpect(jsonPath("$.data.entries.length()").value(2));
        mvc.perform(get("/api/v1/wallets/me").with(token(customer, "CUSTOMER"))).andExpect(status().isForbidden());

        // The ledger is append-only, whoever tries to change it.
        assertThatThrownBy(() -> jdbc.update("UPDATE wallet_entries SET amount = 1"))
                .hasMessageContaining("append-only");
    }

    @Test
    void aDeclinedChargeIsReportedAndNothingIsCredited() throws Exception {
        UUID trip = UuidV7.random();
        UUID driver = UUID.randomUUID();
        // do..when: re-stubbing with when(...) would call the sandbox answer with a null argument.
        doReturn(PaymentProvider.ChargeResult.declined("CARD_DECLINED")).when(provider).charge(any());

        publish(trip, UuidV7.random(), "FareFinalized", fareFinalized(trip, UUID.randomUUID(), driver, 27_000));

        await().ignoreExceptions().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc.queryForMap(
                "SELECT status, failure_code FROM payments WHERE trip_id = ?", trip))
                .containsEntry("status", "FAILED").containsEntry("failure_code", "CARD_DECLINED"));
        JsonNode failed = consume("payment.events.v1", trip.toString());
        assertThat(failed.path("eventType").asText()).isEqualTo("PaymentFailed");
        assertThat(failed.path("payload").path("failureCode").asText()).isEqualTo("CARD_DECLINED");
        assertThat(count("SELECT COUNT(*) FROM wallets WHERE driver_id = ?", driver)).isZero();
    }

    @Test
    void anUnknownOutcomeIsResolvedLaterWithTheSameIdempotencyKey() throws Exception {
        UUID trip = UuidV7.random();
        // Written by the listener thread and the resolver thread.
        List<String> keys = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            String key = ((PaymentProvider.ChargeRequest) call.getArgument(0)).idempotencyKey();
            keys.add(key);
            if (keys.size() == 1) {
                throw new PaymentProvider.ProviderUnavailableException("timeout", null);
            }
            return PaymentProvider.ChargeResult.success("sbx_late");
        }).when(provider).charge(any());

        publish(trip, UuidV7.random(), "FareFinalized", fareFinalized(trip, UUID.randomUUID(), UUID.randomUUID(),
                15_000));

        await().ignoreExceptions().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT status FROM payments WHERE trip_id = ?", String.class, trip)).isEqualTo("SUCCEEDED"));
        verify(provider, atLeast(2)).charge(any());
        assertThat(keys).hasSizeGreaterThanOrEqualTo(2);
        assertThat(keys).containsOnly(keys.getFirst());
        assertThat(count("SELECT COUNT(*) FROM payment_attempts a JOIN payments p ON p.id = a.payment_id "
                + "WHERE p.trip_id = ?", trip)).isEqualTo(1);
    }

    @Test
    void onlyChargeableCancellationsBecomePayments() throws Exception {
        UUID free = UuidV7.random();
        UUID late = UuidV7.random();
        UUID driver = UUID.randomUUID();
        publish(free, UuidV7.random(), "CancellationFeeCalculated", fee(free, UUID.randomUUID(), null, 0,
                "NOT_ASSIGNED"));
        publish(late, UuidV7.random(), "CancellationFeeCalculated", fee(late, UUID.randomUUID(), driver, 10_000,
                "LATE_CANCELLATION"));

        await().ignoreExceptions().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT status FROM payments WHERE trip_id = ? AND purpose = 'CANCELLATION_FEE'", String.class, late))
                .isEqualTo("SUCCEEDED"));
        assertThat(jdbc.queryForObject("SELECT balance FROM wallets WHERE driver_id = ?", Long.class, driver))
                .isEqualTo(8_000);
        assertThat(count("SELECT COUNT(*) FROM payments WHERE trip_id = ?", free)).isZero();
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static Map<String, Object> fareFinalized(UUID trip, UUID customer, UUID driver, long total) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("fareId", UuidV7.randomString());
        p.put("tripId", trip.toString());
        p.put("customerId", customer.toString());
        p.put("driverId", driver.toString());
        p.put("quoteId", UuidV7.randomString());
        p.put("method", "UPFRONT");
        p.put("breakdown", Map.of("baseFare", 12_000, "distanceFare", total - 12_000, "timeFare", 0,
                "minimumFareAdjustment", 0, "surgeAmount", 0, "roundingAdjustment", 0));
        p.put("total", total);
        p.put("currency", "VND");
        p.put("surgeMultiplier", 1.0);
        p.put("pricingRuleVersion", 1);
        p.put("completedAt", Instant.now().toString());
        p.put("finalizedAt", Instant.now().toString());
        p.put("serviceType", "RIDE");
        return p;
    }

    private static Map<String, Object> fee(UUID trip, UUID customer, UUID driver, long fee, String decision) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("feeId", UuidV7.randomString());
        p.put("tripId", trip.toString());
        p.put("customerId", customer.toString());
        p.put("driverId", driver == null ? null : driver.toString());
        p.put("decision", decision);
        p.put("fee", fee);
        p.put("currency", "VND");
        p.put("ruleVersion", 1);
        p.put("cancelledAt", Instant.now().toString());
        p.put("calculatedAt", Instant.now().toString());
        p.put("serviceType", "RIDE");
        return p;
    }

    private void publish(UUID trip, UUID eventId, String type, Map<String, Object> payload) throws Exception {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", type);
        envelope.put("eventVersion", 1);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("correlationId", UuidV7.randomString());
        envelope.put("producer", "pricing-service");
        envelope.put("aggregateId", trip.toString());
        envelope.put("aggregateVersion", 0);
        envelope.put("payload", payload);
        kafkaTemplate.send("pricing.events.v1", trip.toString(), json.writeValueAsString(envelope)).get();
    }

    private int count(String sql, UUID id) {
        Integer value = jdbc.queryForObject(sql, Integer.class, id);
        return value == null ? 0 : value;
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private static RequestPostProcessor token(UUID id, String role) {
        return jwt().jwt(j -> j.subject(id.toString())).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private JsonNode consume(String topic, String key) throws Exception {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        return json.readTree(record.value());
                    }
                }
            }
        }
        throw new AssertionError("No record with key " + key + " on " + topic);
    }
}
