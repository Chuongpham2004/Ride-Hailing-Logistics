package com.rhl.payment.api;

import com.rhl.common.web.ApiResponse;
import com.rhl.payment.application.PaymentQueries;
import com.rhl.payment.domain.PaymentStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Finance staff views (README §3: payments, wallets, commission). Read-only in this version. */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAnyRole('FINANCE_STAFF', 'ADMINISTRATOR')")
public class AdminPaymentController {

    private final PaymentQueries queries;

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

    @GetMapping("/wallets/{driverId}")
    public ApiResponse<PaymentQueries.WalletView> wallet(@PathVariable UUID driverId,
                                                         @RequestParam(required = false) UUID before,
                                                         @RequestParam(defaultValue = "20") @Min(1) @Max(100)
                                                         int limit) {
        return ApiResponse.ok(queries.wallet(driverId, before, limit));
    }
}
