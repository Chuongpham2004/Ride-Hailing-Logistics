package com.rhl.payment.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentDomainTest {

    private static final Instant NOW = Instant.parse("2026-10-07T08:00:00Z");

    @Test
    void aDeclinedPaymentCanBeChargedAgainUntilItSucceeds() {
        Payment payment = payment(27_000);

        PaymentAttempt first = payment.startAttempt(UUID.randomUUID(), "SANDBOX", NOW);
        assertThat(first.getIdempotencyKey()).isEqualTo(payment.getId() + ":1");
        payment.failed(first, "CARD_DECLINED", NOW);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureCode()).isEqualTo("CARD_DECLINED");

        PaymentAttempt second = payment.startAttempt(UUID.randomUUID(), "SANDBOX", NOW);
        assertThat(second.getAttemptNo()).isEqualTo(2);
        // Only the current attempt may settle the payment.
        assertThatThrownBy(() -> payment.succeeded(first, "late", NOW)).isInstanceOf(IllegalStateException.class);
        payment.succeeded(second, "sbx_1", NOW);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getProviderRef()).isEqualTo("sbx_1");
        assertThat(payment.getFailureCode()).isNull();
        assertThatThrownBy(() -> payment.startAttempt(UUID.randomUUID(), "SANDBOX", NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> payment.succeeded(second, "again", NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nothingToChargeIsNotAPayment() {
        assertThatThrownBy(() -> payment(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refundsAddUpToAtMostTheCapturedAmount() {
        Payment payment = paid(27_000);

        Refund first = refund(payment, 10_000);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        assertThat(first.getIdempotencyKey()).isEqualTo("refund:" + first.getId());
        // One refund in flight at a time.
        assertThatThrownBy(() -> refund(payment, 1_000)).isInstanceOf(IllegalStateException.class);
        payment.refunded(first, "sbxr_1", NOW);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(payment.getRefundedAmount()).isEqualTo(10_000);
        assertThat(payment.refundable()).isEqualTo(17_000);

        // BR-010: never more than what is left.
        assertThatThrownBy(() -> refund(payment, 17_001)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> refund(payment, 0)).isInstanceOf(IllegalArgumentException.class);
        Refund rest = refund(payment, 17_000);
        payment.refunded(rest, "sbxr_2", NOW);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.refundable()).isZero();
        assertThatThrownBy(() -> refund(payment, 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> payment.refunded(rest, "again", NOW)).isInstanceOf(IllegalStateException.class);
        // Still captured: a refunded payment is never charged again.
        assertThatThrownBy(() -> payment.startAttempt(UUID.randomUUID(), "SANDBOX", NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aRefusedRefundLeavesThePaymentAsItWas() {
        Payment payment = paid(27_000);
        payment.refunded(refund(payment, 5_000), "sbxr_1", NOW);

        Refund refused = refund(payment, 5_000);
        payment.refundFailed(refused, "REFUND_WINDOW_CLOSED", NOW);

        assertThat(refused.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(refused.getFailureCode()).isEqualTo("REFUND_WINDOW_CLOSED");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(payment.getRefundedAmount()).isEqualTo(5_000);

        Payment untouched = paid(10_000);
        Refund declined = refund(untouched, 10_000);
        untouched.refundFailed(declined, "DECLINED", NOW);
        assertThat(untouched.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void onlyACapturedPaymentCanBeRefunded() {
        Payment unpaid = payment(27_000);
        assertThat(unpaid.refundable()).isZero();
        assertThatThrownBy(() -> refund(unpaid, 1_000)).isInstanceOf(IllegalStateException.class);
    }

    private static Refund refund(Payment payment, long amount) {
        return payment.startRefund(UUID.randomUUID(), amount, RefundReason.OVERCHARGE, null, "SANDBOX",
                UUID.randomUUID().toString(), "hash", UUID.randomUUID(), NOW);
    }

    private static Payment payment(long amount) {
        return Payment.open(UUID.randomUUID(), UUID.randomUUID(), PaymentPurpose.TRIP_FARE, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), ServiceType.RIDE, amount, "VND", NOW);
    }

    private static Payment paid(long amount) {
        Payment payment = payment(amount);
        payment.succeeded(payment.startAttempt(UUID.randomUUID(), "SANDBOX", NOW), "sbx", NOW);
        return payment;
    }
}
