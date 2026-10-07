package com.rhl.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rhl.common.id.UuidV7;
import com.rhl.payment.application.PaymentProvider;
import com.rhl.payment.infrastructure.provider.WebhookSignature;
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
import org.springframework.http.MediaType;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
        "rhl.outbox.poll-interval=50ms",
        "rhl.charge.max-attempts=3",
        "rhl.provider.webhook-secret=" + PaymentServiceIT.SECRET})
class PaymentServiceIT {

    static final String SECRET = "it-webhook-secret-0123456789";

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
        when(provider.refund(any())).thenAnswer(call ->
                PaymentProvider.ChargeResult.success("sbxr_" + ((PaymentProvider.RefundRequest) call.getArgument(0))
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

    /** FR-PAY: asynchronous outcomes arrive as signed callbacks; repeats and replays change nothing. */
    @Test
    void signedCallbacksSettleAcceptedChargesExactlyOnce() throws Exception {
        UUID trip = UuidV7.random();
        UUID driver = UUID.randomUUID();
        doReturn(PaymentProvider.ChargeResult.pending("prov_123")).when(provider).charge(any());

        publish(trip, UuidV7.random(), "FareFinalized", fareFinalized(trip, UUID.randomUUID(), driver, 27_000));
        await().ignoreExceptions().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT a.provider_ref FROM payment_attempts a JOIN payments p ON p.id = a.payment_id "
                        + "WHERE p.trip_id = ?", String.class, trip)).isEqualTo("prov_123"));
        String key = jdbc.queryForObject("SELECT a.idempotency_key FROM payment_attempts a JOIN payments p "
                + "ON p.id = a.payment_id WHERE p.trip_id = ?", String.class, trip);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE trip_id = ?", String.class, trip))
                .isEqualTo("PENDING");

        String succeeded = callback("evt_" + trip, "charge.succeeded", key, 27_000, "VND");
        // Wrong secret, then a signature from 10 minutes ago: refused before anything is read.
        mvc.perform(post("/api/v1/payments/callbacks/sandbox").contentType(MediaType.APPLICATION_JSON)
                        .header(WebhookSignature.HEADER, WebhookSignature.header("wrong-secret-0123456789",
                                Instant.now(), succeeded))
                        .content(succeeded))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/payments/callbacks/sandbox").contentType(MediaType.APPLICATION_JSON)
                        .header(WebhookSignature.HEADER, WebhookSignature.header(SECRET,
                                Instant.now().minus(Duration.ofMinutes(10)), succeeded))
                        .content(succeeded))
                .andExpect(status().isUnauthorized());

        sendCallback(succeeded).andExpect(jsonPath("$.data.outcome").value("APPLIED"));
        sendCallback(succeeded).andExpect(jsonPath("$.data.outcome").value("DUPLICATE"));
        // A later, conflicting outcome for the same attempt (new event ID) is recorded but not applied.
        sendCallback(callback("evt_late_" + trip, "charge.failed", key, 27_000, "VND"))
                .andExpect(jsonPath("$.data.outcome").value("ALREADY_RESOLVED"));

        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE trip_id = ?", String.class, trip))
                .isEqualTo("SUCCEEDED");
        await().ignoreExceptions().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT balance FROM wallets WHERE driver_id = ?", Long.class, driver)).isEqualTo(21_600));
        assertThat(count("SELECT COUNT(*) FROM wallet_entries e JOIN wallets w ON w.id = e.wallet_id "
                + "WHERE w.driver_id = ?", driver)).isEqualTo(2);

        // Wrong amount or unknown attempt: recorded, never applied.
        sendCallback(callback("evt_unknown_" + trip, "charge.succeeded", "nope:1", 27_000, "VND"))
                .andExpect(jsonPath("$.data.outcome").value("REJECTED"));
        UUID other = UuidV7.random();
        publish(other, UuidV7.random(), "FareFinalized", fareFinalized(other, UUID.randomUUID(), driver, 15_000));
        await().ignoreExceptions().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT a.provider_ref FROM payment_attempts a JOIN payments p ON p.id = a.payment_id "
                        + "WHERE p.trip_id = ?", String.class, other)).isEqualTo("prov_123"));
        String otherKey = jdbc.queryForObject("SELECT a.idempotency_key FROM payment_attempts a JOIN payments p "
                + "ON p.id = a.payment_id WHERE p.trip_id = ?", String.class, other);
        sendCallback(callback("evt_amount_" + other, "charge.succeeded", otherKey, 1, "VND"))
                .andExpect(jsonPath("$.data.outcome").value("REJECTED"));
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE trip_id = ?", String.class, other))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT reason FROM provider_callbacks WHERE provider_event_id = ?",
                String.class, "evt_amount_" + other)).isEqualTo("AMOUNT_MISMATCH");
    }

    @Test
    void customersRetryFailedPaymentsUpToTheLimit() throws Exception {
        UUID trip = UuidV7.random();
        UUID customer = UUID.randomUUID();
        doReturn(PaymentProvider.ChargeResult.declined("CARD_DECLINED")).when(provider).charge(any());
        publish(trip, UuidV7.random(), "FareFinalized", fareFinalized(trip, customer, UUID.randomUUID(), 27_000));
        await().ignoreExceptions().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT status FROM payments WHERE trip_id = ?", String.class, trip)).isEqualTo("FAILED"));
        String paymentId = jdbc.queryForObject("SELECT id::text FROM payments WHERE trip_id = ?", String.class, trip);

        mvc.perform(post("/api/v1/payments/" + paymentId + "/retry").with(token(UUID.randomUUID(), "CUSTOMER")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/payments/" + paymentId + "/retry").with(token(customer, "CUSTOMER")))
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.attemptCount").value(2));
        mvc.perform(post("/api/v1/payments/" + paymentId + "/retry").with(token(customer, "CUSTOMER")))
                .andExpect(jsonPath("$.data.attemptCount").value(3));
        // rhl.charge.max-attempts=3
        mvc.perform(post("/api/v1/payments/" + paymentId + "/retry").with(token(customer, "CUSTOMER")))
                .andExpect(status().isUnprocessableEntity());

        // Finance staff see the history; customers cannot use the finance API.
        mvc.perform(get("/api/v1/admin/payments/" + paymentId + "/attempts").with(token(UUID.randomUUID(),
                        "FINANCE_STAFF")))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[2].failureCode").value("CARD_DECLINED"));
        mvc.perform(get("/api/v1/admin/payments").param("status", "FAILED").with(token(UUID.randomUUID(),
                        "FINANCE_STAFF")))
                .andExpect(jsonPath("$.data.items[?(@.id == '" + paymentId + "')]").exists());
        mvc.perform(get("/api/v1/admin/payments").with(token(customer, "CUSTOMER"))).andExpect(status().isForbidden());
    }

    @Test
    void aRetryAfterADeclineCanSucceed() throws Exception {
        UUID trip = UuidV7.random();
        UUID customer = UUID.randomUUID();
        UUID driver = UUID.randomUUID();
        doReturn(PaymentProvider.ChargeResult.declined("INSUFFICIENT_FUNDS")).when(provider).charge(any());
        publish(trip, UuidV7.random(), "FareFinalized", fareFinalized(trip, customer, driver, 27_000));
        await().ignoreExceptions().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT status FROM payments WHERE trip_id = ?", String.class, trip)).isEqualTo("FAILED"));
        String paymentId = jdbc.queryForObject("SELECT id::text FROM payments WHERE trip_id = ?", String.class, trip);

        doReturn(PaymentProvider.ChargeResult.success("sbx_retry")).when(provider).charge(any());
        mvc.perform(post("/api/v1/payments/" + paymentId + "/retry").with(token(customer, "CUSTOMER")))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.providerRef").value("sbx_retry"));
        // Paid: retrying again is a no-op, not a second charge.
        mvc.perform(post("/api/v1/payments/" + paymentId + "/retry").with(token(customer, "CUSTOMER")))
                .andExpect(jsonPath("$.data.attemptCount").value(2));
        assertThat(jdbc.queryForObject("SELECT balance FROM wallets WHERE driver_id = ?", Long.class, driver))
                .isEqualTo(21_600);
    }

    /** FR-PAY, BR-010: partial and full refunds, never above what was captured, idempotent per key. */
    @Test
    void financeRefundsInPartsUpToTheCapturedAmountExactlyOnce() throws Exception {
        UUID trip = UuidV7.random();
        UUID customer = UUID.randomUUID();
        UUID driver = UUID.randomUUID();
        String paymentId = paidFare(trip, customer, driver, 27_000);
        UUID finance = UUID.randomUUID();

        String first = refundBody(10_000L, "OVERCHARGE", "Route was longer than quoted");
        String refundId = data(refund(paymentId, "refund-key-0001", first, finance).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.amount").value(10_000))).path("id").asText();
        // Sent again: the same refund, not a second one.
        refund(paymentId, "refund-key-0001", first, finance)
                .andExpect(jsonPath("$.data.id").value(refundId));
        verify(provider, times(1)).refund(any());
        refund(paymentId, "refund-key-0001", refundBody(9_000L, "OVERCHARGE", null), finance)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jdbc.queryForMap("SELECT status, refunded_amount FROM payments WHERE id = ?::uuid", paymentId))
                .containsEntry("status", "PARTIALLY_REFUNDED").containsEntry("refunded_amount", 10_000L);

        // BR-010: 17 000 is left.
        refund(paymentId, "refund-key-0002", refundBody(17_001L, "GOODWILL", null), finance)
                .andExpect(status().isUnprocessableEntity());
        refund(paymentId, "refund-key-0003", refundBody(null, "OTHER", null), finance)
                .andExpect(status().isBadRequest());
        refund(paymentId, "refund-key-0004", refundBody(null, "SERVICE_COMPLAINT", null), finance)
                .andExpect(jsonPath("$.data.amount").value(17_000));
        refund(paymentId, "refund-key-0005", refundBody(1L, "GOODWILL", null), finance)
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForMap("SELECT status, refunded_amount FROM payments WHERE id = ?::uuid", paymentId))
                .containsEntry("status", "REFUNDED").containsEntry("refunded_amount", 27_000L);
        // The database refuses an over-refund even if the checks were bypassed.
        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET refunded_amount = amount + 1 WHERE id = ?::uuid",
                paymentId)).hasMessageContaining("violates check constraint");

        JsonNode completed = consume("payment.events.v1", trip.toString(), "RefundCompleted");
        assertThat(completed.path("payload").path("refundId").asText()).isEqualTo(refundId);
        assertThat(completed.path("payload").path("refundedTotal").asLong()).isEqualTo(10_000);
        assertThat(completed.path("payload").path("paymentStatus").asText()).isEqualTo("PARTIALLY_REFUNDED");

        // The customer sees amounts and status, not the internal note; nobody else sees anything.
        mvc.perform(get("/api/v1/payments/" + paymentId + "/refunds").with(token(customer, "CUSTOMER")))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].amount").value(10_000))
                .andExpect(jsonPath("$.data[0].note").doesNotExist());
        mvc.perform(get("/api/v1/payments/" + paymentId + "/refunds").with(token(finance, "FINANCE_STAFF")))
                .andExpect(jsonPath("$.data[0].note").value("Route was longer than quoted"));
        mvc.perform(get("/api/v1/payments/" + paymentId + "/refunds").with(token(UUID.randomUUID(), "CUSTOMER")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/admin/payments/" + paymentId + "/refunds").with(token(customer, "CUSTOMER"))
                        .header("Idempotency-Key", "refund-key-0006").contentType(MediaType.APPLICATION_JSON)
                        .content(refundBody(1L, "GOODWILL", null)))
                .andExpect(status().isForbidden());

        // Audited (BR-014); the driver's wallet is untouched until finance claws back.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_records WHERE action = 'REFUND_REQUESTED' "
                + "AND target_id = ? AND actor_id = ?", Integer.class, paymentId, finance)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT balance FROM wallets WHERE driver_id = ?", Long.class, driver))
                .isEqualTo(21_600);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_records")).hasMessageContaining("append-only");
    }

    @Test
    void aRefundWithAnUnknownOutcomeIsSentAgainWithTheSameKey() throws Exception {
        UUID trip = UuidV7.random();
        String paymentId = paidFare(trip, UUID.randomUUID(), UUID.randomUUID(), 27_000);
        // Written by the request thread and the resolver thread.
        List<String> keys = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            keys.add(((PaymentProvider.RefundRequest) call.getArgument(0)).idempotencyKey());
            if (keys.size() == 1) {
                throw new PaymentProvider.ProviderUnavailableException("timeout", null);
            }
            return PaymentProvider.ChargeResult.success("sbxr_late");
        }).when(provider).refund(any());

        refund(paymentId, "refund-key-0101", refundBody(5_000L, "GOODWILL", null), UUID.randomUUID())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        // While it is in flight, no other refund is accepted.
        refund(paymentId, "refund-key-0102", refundBody(1_000L, "GOODWILL", null), UUID.randomUUID())
                .andExpect(status().isConflict());

        await().ignoreExceptions().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT status FROM refunds WHERE payment_id = ?::uuid", String.class, paymentId))
                .isEqualTo("SUCCEEDED"));
        assertThat(keys).hasSizeGreaterThanOrEqualTo(2).containsOnly(keys.getFirst());
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?::uuid", String.class, paymentId))
                .isEqualTo("PARTIALLY_REFUNDED");
    }

    @Test
    void refundOutcomesArriveBySignedCallbackAndADeclineCanBeRequestedAgain() throws Exception {
        UUID trip = UuidV7.random();
        String paymentId = paidFare(trip, UUID.randomUUID(), UUID.randomUUID(), 27_000);
        doReturn(PaymentProvider.ChargeResult.pending("prov_r1")).when(provider).refund(any());

        String first = data(refund(paymentId, "refund-key-0201", refundBody(27_000L, "SERVICE_NOT_PROVIDED", null),
                UUID.randomUUID()).andExpect(jsonPath("$.data.status").value("PENDING"))).path("id").asText();
        sendCallback(callback("evt_rf_" + first, "refund.failed", "refund:" + first, 27_000, "VND"))
                .andExpect(jsonPath("$.data.outcome").value("APPLIED"));
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?::uuid", String.class, paymentId))
                .isEqualTo("SUCCEEDED");

        String second = data(refund(paymentId, "refund-key-0202", refundBody(27_000L, "SERVICE_NOT_PROVIDED",
                null), UUID.randomUUID())).path("id").asText();
        String secondKey = "refund:" + second;
        sendCallback(callback("evt_amt_" + second, "refund.succeeded", secondKey, 1, "VND"))
                .andExpect(jsonPath("$.data.outcome").value("REJECTED"));
        String succeeded = callback("evt_ok_" + second, "refund.succeeded", secondKey, 27_000, "VND");
        sendCallback(succeeded).andExpect(jsonPath("$.data.outcome").value("APPLIED"));
        sendCallback(succeeded).andExpect(jsonPath("$.data.outcome").value("DUPLICATE"));
        sendCallback(callback("evt_late_" + second, "refund.failed", secondKey, 27_000, "VND"))
                .andExpect(jsonPath("$.data.outcome").value("ALREADY_RESOLVED"));

        assertThat(jdbc.queryForMap("SELECT status, refunded_amount FROM payments WHERE id = ?::uuid", paymentId))
                .containsEntry("status", "REFUNDED").containsEntry("refunded_amount", 27_000L);
        assertThat(jdbc.queryForObject("SELECT refund_id::text FROM provider_callbacks WHERE provider_event_id = ?",
                String.class, "evt_ok_" + second)).isEqualTo(second);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_records WHERE action = 'REFUND_SETTLED' "
                + "AND target_id IN (?, ?)", Integer.class, first, second)).isEqualTo(2);
    }

    /** BR-011, BR-012, FR-WAL-009: corrections are new ledger lines, idempotent, audited, never overdrawn. */
    @Test
    void walletAdjustmentsAreIdempotentAuditedAndNeverOverdrawn() throws Exception {
        UUID trip = UuidV7.random();
        UUID driver = UUID.randomUUID();
        UUID finance = UUID.randomUUID();
        String paymentId = paidFare(trip, UUID.randomUUID(), driver, 27_000);
        String refundId = data(refund(paymentId, "refund-key-0301", refundBody(10_000L, "OVERCHARGE", null),
                finance)).path("id").asText();

        String clawback = adjustmentBody(-8_000, "REFUND_CLAWBACK", null, refundId);
        String adjustmentId = data(adjust(driver, "adjust-key-0001", clawback, finance).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balanceAfter").value(13_600))
                .andExpect(jsonPath("$.data.tripId").value(trip.toString()))).path("id").asText();
        adjust(driver, "adjust-key-0001", clawback, finance)
                .andExpect(jsonPath("$.data.id").value(adjustmentId))
                .andExpect(jsonPath("$.data.balanceAfter").value(13_600));
        adjust(driver, "adjust-key-0001", adjustmentBody(-7_000, "REFUND_CLAWBACK", null, refundId), finance)
                .andExpect(status().isConflict());
        // At most the refunded 10 000 is taken back.
        adjust(driver, "adjust-key-0002", adjustmentBody(-2_001, "REFUND_CLAWBACK", null, refundId), finance)
                .andExpect(status().isUnprocessableEntity());
        adjust(driver, "adjust-key-0003", adjustmentBody(1_000, "REFUND_CLAWBACK", null, refundId), finance)
                .andExpect(status().isBadRequest());
        // FR-WAL-009: never below zero.
        adjust(driver, "adjust-key-0004", adjustmentBody(-13_601, "EARNING_CORRECTION", null, null), finance)
                .andExpect(status().isUnprocessableEntity());
        adjust(driver, "adjust-key-0005", adjustmentBody(2_000, "OTHER", null, null), finance)
                .andExpect(status().isBadRequest());
        adjust(driver, "adjust-key-0006", adjustmentBody(2_000, "OTHER", "Fuel bonus October", null), finance)
                .andExpect(jsonPath("$.data.balanceAfter").value(15_600));
        adjust(UUID.randomUUID(), "adjust-key-0007", adjustmentBody(2_000, "INCENTIVE", null, null), finance)
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/admin/wallets/" + driver + "/adjustments").with(token(driver, "DRIVER"))
                        .header("Idempotency-Key", "adjust-key-0008").contentType(MediaType.APPLICATION_JSON)
                        .content(adjustmentBody(1_000_000, "INCENTIVE", null, null)))
                .andExpect(status().isForbidden());

        // The ledger sums to the balance and shows what each line was for.
        assertThat(jdbc.queryForObject("SELECT SUM(e.amount) FROM wallet_entries e JOIN wallets w "
                + "ON w.id = e.wallet_id WHERE w.driver_id = ?", Long.class, driver)).isEqualTo(15_600);
        mvc.perform(get("/api/v1/wallets/me").with(token(driver, "DRIVER")))
                .andExpect(jsonPath("$.data.balance").value(15_600))
                .andExpect(jsonPath("$.data.entries.length()").value(4))
                .andExpect(jsonPath("$.data.entries[1].type").value("ADJUSTMENT"))
                .andExpect(jsonPath("$.data.entries[1].referenceId").value(adjustmentId));
        JsonNode adjusted = consume("wallet.events.v1", driver.toString(), "WalletAdjusted");
        assertThat(adjusted.path("payload").path("amount").asLong()).isEqualTo(-8_000);
        assertThat(adjusted.path("payload").path("refundId").asText()).isEqualTo(refundId);
        assertThat(adjusted.path("payload").path("balance").asLong()).isEqualTo(13_600);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_records WHERE action = 'WALLET_ADJUSTED' "
                + "AND actor_id = ?", Integer.class, finance)).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update("UPDATE wallet_adjustments SET amount = 1"))
                .hasMessageContaining("append-only");
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** A FareFinalized that was charged successfully; returns the payment ID. */
    private String paidFare(UUID trip, UUID customer, UUID driver, long total) throws Exception {
        publish(trip, UuidV7.random(), "FareFinalized", fareFinalized(trip, customer, driver, total));
        await().ignoreExceptions().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT status FROM payments WHERE trip_id = ?", String.class, trip)).isEqualTo("SUCCEEDED"));
        return jdbc.queryForObject("SELECT id::text FROM payments WHERE trip_id = ?", String.class, trip);
    }

    private ResultActions refund(String paymentId, String key, String body, UUID finance) throws Exception {
        return mvc.perform(post("/api/v1/admin/payments/" + paymentId + "/refunds")
                .with(token(finance, "FINANCE_STAFF")).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions adjust(UUID driver, String key, String body, UUID finance) throws Exception {
        return mvc.perform(post("/api/v1/admin/wallets/" + driver + "/adjustments")
                .with(token(finance, "FINANCE_STAFF")).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String refundBody(Long amount, String reason, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amount);
        body.put("reason", reason);
        body.put("note", note);
        return json.writeValueAsString(body);
    }

    private String adjustmentBody(long amount, String reason, String note, String refundId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amount);
        body.put("reason", reason);
        body.put("note", note);
        body.put("refundId", refundId);
        return json.writeValueAsString(body);
    }

    private String callback(String eventId, String type, String key, long amount, String currency) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", eventId);
        body.put("type", type);
        body.put("idempotencyKey", key);
        body.put("reference", "prov_123");
        body.put("amount", amount);
        body.put("currency", currency);
        if ("charge.failed".equals(type)) {
            body.put("failureCode", "CARD_DECLINED");
        }
        return json.writeValueAsString(body);
    }

    private ResultActions sendCallback(String body) throws Exception {
        return mvc.perform(post("/api/v1/payments/callbacks/sandbox").contentType(MediaType.APPLICATION_JSON)
                        .header(WebhookSignature.HEADER, WebhookSignature.header(SECRET, Instant.now(), body))
                        .content(body))
                .andExpect(status().isOk());
    }

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
        return consume(topic, key, null);
    }

    /** The first record with this key, and this event type when given. */
    private JsonNode consume(String topic, String key, String eventType) throws Exception {
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
                    JsonNode value = json.readTree(record.value());
                    if (key.equals(record.key())
                            && (eventType == null || eventType.equals(value.path("eventType").asText()))) {
                        return value;
                    }
                }
            }
        }
        throw new AssertionError("No record with key " + key + " on " + topic);
    }
}
