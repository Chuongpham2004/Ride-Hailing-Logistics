package com.rhl.payment.api;

import com.rhl.common.security.CurrentUser;
import com.rhl.common.web.ApiResponse;
import com.rhl.payment.application.PaymentQueries;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Read-only: payments are created from pricing events, never by clients (FR-PAY). */
@Validated
@RestController
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentQueries queries;

    @GetMapping("/api/v1/payments/{paymentId}")
    public ApiResponse<PaymentQueries.PaymentView> payment(@PathVariable UUID paymentId) {
        return ApiResponse.ok(queries.payment(CurrentUser.get(), paymentId));
    }

    /** Payments of one trip visible to the caller: their own, or all of them for finance staff. */
    @GetMapping("/api/v1/payments")
    public ApiResponse<List<PaymentQueries.PaymentView>> forTrip(@RequestParam UUID tripId) {
        return ApiResponse.ok(queries.forTrip(CurrentUser.get(), tripId));
    }

    /** The driver's balance and ledger, newest first (FR-WAL). */
    @GetMapping("/api/v1/wallets/me")
    @PreAuthorize("hasRole('DRIVER')")
    public ApiResponse<PaymentQueries.WalletView> myWallet(@RequestParam(required = false) UUID before,
                                                           @RequestParam(defaultValue = "20") @Min(1) @Max(100)
                                                           int limit) {
        return ApiResponse.ok(queries.wallet(CurrentUser.get().id(), before, limit));
    }
}
