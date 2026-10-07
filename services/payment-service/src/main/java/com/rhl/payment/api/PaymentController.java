package com.rhl.payment.api;

import com.rhl.common.security.CurrentUser;
import com.rhl.common.web.ApiResponse;
import com.rhl.payment.application.PaymentProcessor;
import com.rhl.payment.application.PaymentQueries;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Payments are created from pricing events, never by clients (FR-PAY); customers can only retry failed ones. */
@Validated
@RestController
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentQueries queries;
    private final PaymentProcessor processor;

    @GetMapping("/api/v1/payments/{paymentId}")
    public ApiResponse<PaymentQueries.PaymentView> payment(@PathVariable UUID paymentId) {
        return ApiResponse.ok(queries.payment(CurrentUser.get(), paymentId));
    }

    /** Payments of one trip visible to the caller: their own, or all of them for finance staff. */
    @GetMapping("/api/v1/payments")
    public ApiResponse<List<PaymentQueries.PaymentView>> forTrip(@RequestParam UUID tripId) {
        return ApiResponse.ok(queries.forTrip(CurrentUser.get(), tripId));
    }

    /** Refunds of a payment: the customer who paid, or finance staff (FR-PAY). */
    @GetMapping("/api/v1/payments/{paymentId}/refunds")
    public ApiResponse<List<PaymentQueries.RefundView>> refunds(@PathVariable UUID paymentId) {
        return ApiResponse.ok(queries.refunds(CurrentUser.get(), paymentId));
    }

    /** Pay a failed payment again (FR-PAY); a no-op while one is in flight or once paid. */
    @PostMapping("/api/v1/payments/{paymentId}/retry")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<PaymentQueries.PaymentView> retry(@PathVariable UUID paymentId) {
        return ApiResponse.ok(processor.retry(CurrentUser.get(), paymentId));
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
