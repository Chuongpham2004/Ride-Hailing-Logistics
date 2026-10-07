package com.rhl.payment.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rhl.common.id.UuidV7;
import com.rhl.common.web.ApiException;
import com.rhl.common.web.ErrorCode;
import com.rhl.payment.PaymentServiceProperties;
import com.rhl.payment.domain.Payment;
import com.rhl.payment.domain.Refund;
import com.rhl.payment.infrastructure.persistence.PaymentAttemptRepository;
import com.rhl.payment.infrastructure.persistence.PaymentRepository;
import com.rhl.payment.infrastructure.persistence.ProviderCallbackRepository;
import com.rhl.payment.infrastructure.persistence.RefundRepository;
import com.rhl.payment.infrastructure.provider.WebhookSignature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Provider webhooks (FR-PAY): the signature and its timestamp are verified before anything is
 * read, every event ID is recorded once (replays and redeliveries are recognised), the amount
 * and currency must match the payment (or refund), and the outcome goes through the same
 * idempotent step as synchronous answers, so a repeated or late callback never charges, refunds
 * or credits twice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallbackService {

    public static final String SUCCEEDED = "charge.succeeded";
    public static final String FAILED = "charge.failed";
    public static final String REFUND_SUCCEEDED = "refund.succeeded";
    public static final String REFUND_FAILED = "refund.failed";

    private final ObjectMapper objectMapper;
    private final ProviderCallbackRepository callbacks;
    private final PaymentAttemptRepository attempts;
    private final PaymentRepository payments;
    private final PaymentSteps steps;
    private final RefundRepository refundRepository;
    private final RefundSteps refundSteps;
    private final PaymentProvider provider;
    private final PaymentServiceProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    /** What happened to a callback; the provider only needs a 2xx to stop re-sending. */
    public record Ack(String outcome) {
    }

    public Ack handle(String providerName, String signature, String body) {
        if (!provider.name().equalsIgnoreCase(providerName)) {
            throw ApiException.notFound("Provider");
        }
        Instant now = clock.instant();
        Instant signedAt;
        try {
            signedAt = WebhookSignature.verify(signature, body, properties.provider().webhookSecret(), now,
                    properties.provider().signatureTolerance());
        } catch (WebhookSignature.InvalidSignatureException e) {
            log.warn("Rejected provider callback: {}", e.getMessage());
            throw new ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "Invalid callback signature");
        }
        JsonNode json;
        try {
            json = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Malformed callback");
        }
        String eventId = json.path("eventId").asText("");
        String type = json.path("type").asText("");
        String key = json.path("idempotencyKey").asText("");
        if (eventId.isBlank() || type.isBlank() || key.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Callback needs eventId, type and idempotencyKey");
        }
        return tx.execute(status -> apply(json, eventId, type, key, body, signedAt, now));
    }

    private Ack apply(JsonNode json, String eventId, String type, String key, String body, Instant signedAt,
                      Instant now) {
        UUID callbackId = UuidV7.random();
        if (!callbacks.receive(callbackId, provider.name(), eventId, type, key, body, signedAt, now)) {
            return new Ack("DUPLICATE");
        }
        return switch (type) {
            case SUCCEEDED, FAILED -> applyCharge(callbackId, json, eventId, type, key);
            case REFUND_SUCCEEDED, REFUND_FAILED -> applyRefund(callbackId, json, eventId, type, key);
            default -> reject(callbackId, null, null, "UNSUPPORTED_TYPE");
        };
    }

    private Ack applyCharge(UUID callbackId, JsonNode json, String eventId, String type, String key) {
        Optional<UUID> attemptId = attempts.findIdByKey(key, provider.name());
        if (attemptId.isEmpty()) {
            return reject(callbackId, null, null, "UNKNOWN_ATTEMPT");
        }
        UUID paymentId = attempts.findPaymentId(attemptId.get()).orElseThrow();
        Payment payment = payments.findByIdForUpdate(paymentId).orElseThrow();
        if (!matches(json, payment.getAmount(), payment.getCurrency())) {
            log.error("Provider callback {} does not match payment {} amount/currency", eventId, paymentId);
            return reject(callbackId, paymentId, null, "AMOUNT_MISMATCH");
        }
        String outcome = steps.complete(attemptId.get(), result(json, type, key)) ? "APPLIED" : "ALREADY_RESOLVED";
        callbacks.resolve(callbackId, outcome, paymentId, null, null);
        return new Ack(outcome);
    }

    private Ack applyRefund(UUID callbackId, JsonNode json, String eventId, String type, String key) {
        Optional<UUID> refundId = refundRepository.findIdByKey(key, provider.name());
        if (refundId.isEmpty()) {
            return reject(callbackId, null, null, "UNKNOWN_REFUND");
        }
        UUID paymentId = refundRepository.findPaymentId(refundId.get()).orElseThrow();
        payments.findByIdForUpdate(paymentId).orElseThrow();
        Refund refund = refundRepository.findById(refundId.get()).orElseThrow();
        if (!matches(json, refund.getAmount(), refund.getCurrency())) {
            log.error("Provider callback {} does not match refund {} amount/currency", eventId, refund.getId());
            return reject(callbackId, paymentId, refund.getId(), "AMOUNT_MISMATCH");
        }
        String outcome = refundSteps.complete(refund.getId(), result(json, type, key))
                ? "APPLIED" : "ALREADY_RESOLVED";
        callbacks.resolve(callbackId, outcome, paymentId, refund.getId(), null);
        return new Ack(outcome);
    }

    private static boolean matches(JsonNode json, long amount, String currency) {
        return json.path("amount").asLong(-1) == amount && currency.equals(json.path("currency").asText());
    }

    private static PaymentProvider.ChargeResult result(JsonNode json, String type, String key) {
        return SUCCEEDED.equals(type) || REFUND_SUCCEEDED.equals(type)
                ? PaymentProvider.ChargeResult.success(json.path("reference").asText(key))
                : PaymentProvider.ChargeResult.declined(json.path("failureCode").asText("DECLINED"));
    }

    private Ack reject(UUID callbackId, UUID paymentId, UUID refundId, String reason) {
        callbacks.resolve(callbackId, "REJECTED", paymentId, refundId, reason);
        return new Ack("REJECTED");
    }
}
