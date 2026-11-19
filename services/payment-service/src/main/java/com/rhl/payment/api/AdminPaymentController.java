package com.rhl.payment.api;

import com.rhl.common.security.CurrentUser;
import com.rhl.common.web.ApiResponse;
import com.rhl.payment.application.PaymentQueries;
import com.rhl.payment.application.RefundProcessor;
import com.rhl.payment.domain.PaymentStatus;
import com.rhl.payment.domain.RefundReason;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Finance staff (README §3): payments and refunds (FR-PAY). Driver wallets are kept by the
 * payment provider, not here. Writes require {@code Idempotency-Key} (COM-008): the same request sent
 * again returns the first result, a different one under the same key is refused.
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAnyRole('FINANCE_STAFF', 'ADMINISTRATOR')")
public class AdminPaymentController {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String KEY_PATTERN = "^[A-Za-z0-9_-]{8,100}$";

    private final PaymentQueries queries;
    private final RefundProcessor refunds;
    private final ObjectMapper objectMapper;

    /** @param amount what to give back; omitted for everything not refunded yet */
    public record RefundRequest(@Positive Long amount, @NotNull RefundReason reason, @Size(max = 500) String note) {
    }

    @GetMapping("/payments")
    public ApiResponse<PaymentQueries.PaymentPage> payments(@RequestParam(required = false) PaymentStatus status,
                                                           @RequestParam(required = false) UUID before,
                                                           @RequestParam(defaultValue = "20") @Min(1) @Max(100)
                                                           int limit) {
        return ApiResponse.ok(queries.list(status, before, limit));
    }

    @GetMapping("/payments/{paymentId}/attempts")
    public ApiResponse<List<PaymentQueries.AttemptView>> attempts(@PathVariable UUID paymentId) {
        return ApiResponse.ok(queries.attempts(paymentId));
    }

    /** Refunds a paid payment in full or in part (FR-PAY, BR-010); the provider's answer may come later. */
    @PostMapping("/payments/{paymentId}/refunds")
    public ApiResponse<PaymentQueries.RefundView> refund(@PathVariable UUID paymentId,
                                                         @RequestHeader(IDEMPOTENCY_KEY) @Pattern(regexp = KEY_PATTERN)
                                                         String idempotencyKey,
                                                         @Valid @RequestBody RefundRequest request) {
        return ApiResponse.ok(refunds.refund(CurrentUser.get().id(), paymentId, request.amount(), request.reason(),
                request.note(), idempotencyKey, hash(request)));
    }

    private String hash(Object request) {
        try {
            byte[] body = objectMapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (JacksonException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot hash the request", e);
        }
    }
}
