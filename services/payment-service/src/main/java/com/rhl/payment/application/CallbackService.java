package com.rhl.payment.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rhl.common.id.UuidV7;
import com.rhl.common.web.ApiException;
import com.rhl.common.web.ErrorCode;
import com.rhl.payment.PaymentServiceProperties;
import com.rhl.payment.domain.Payment;
import com.rhl.payment.infrastructure.persistence.PaymentAttemptRepository;
import com.rhl.payment.infrastructure.persistence.PaymentRepository;
import com.rhl.payment.infrastructure.persistence.ProviderCallbackRepository;
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
 * and currency must match the payment, and the outcome goes through the same idempotent step as
 * synchronous answers, so a repeated or late callback never charges or credits twice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallbackService {

    public static final String SUCCEEDED = "charge.succeeded";
    public static final String FAILED = "charge.failed";

    private final ObjectMapper objectMapper;
    private final ProviderCallbackRepository callbacks;
    private final PaymentAttemptRepository attempts;
    private final PaymentRepository payments;
    private final PaymentSteps steps;
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
        if (!SUCCEEDED.equals(type) && !FAILED.equals(type)) {
            return reject(callbackId, null, "UNSUPPORTED_TYPE");
        }
        Optional<UUID> attemptId = attempts.findIdByKey(key, provider.name());
        if (attemptId.isEmpty()) {
            return reject(callbackId, null, "UNKNOWN_ATTEMPT");
        }
        UUID paymentId = attempts.findPaymentId(attemptId.get()).orElseThrow();
        Payment payment = payments.findByIdForUpdate(paymentId).orElseThrow();
        if (json.path("amount").asLong(-1) != payment.getAmount()
                || !payment.getCurrency().equals(json.path("currency").asText())) {
            log.error("Provider callback {} does not match payment {} amount/currency", eventId, paymentId);
            return reject(callbackId, paymentId, "AMOUNT_MISMATCH");
        }
        PaymentProvider.ChargeResult result = SUCCEEDED.equals(type)
                ? PaymentProvider.ChargeResult.success(json.path("reference").asText(key))
                : PaymentProvider.ChargeResult.declined(json.path("failureCode").asText("DECLINED"));
        String outcome = steps.complete(attemptId.get(), result) ? "APPLIED" : "ALREADY_RESOLVED";
        callbacks.resolve(callbackId, outcome, paymentId, null);
        return new Ack(outcome);
    }

    private Ack reject(UUID callbackId, UUID paymentId, String reason) {
        callbacks.resolve(callbackId, "REJECTED", paymentId, reason);
        return new Ack("REJECTED");
    }
}
