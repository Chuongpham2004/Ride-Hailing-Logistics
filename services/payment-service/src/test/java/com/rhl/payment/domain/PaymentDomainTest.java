package com.rhl.payment.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentDomainTest {

    private static final Instant NOW = Instant.parse("2026-10-07T08:00:00Z");
    private static final CommissionRule TWENTY_PERCENT = CommissionRule.of(ServiceType.RIDE, 1, "0.2000");

    @ParameterizedTest
    @CsvSource({
            // gross, commission (half-up to whole VND), net
            "27000, 5400, 21600",
            "12347, 2469, 9878",
            "12348, 2470, 9878",
            "1, 0, 1",
            "0, 0, 0"})
    void commissionIsRoundedHalfUpAndNetIsWhatIsLeft(long gross, long commission, long net) {
        CommissionRule.Split split = TWENTY_PERCENT.split(gross);

        assertThat(split.commission()).isEqualTo(commission);
        assertThat(split.net()).isEqualTo(net);
        assertThat(split.gross()).isEqualTo(split.commission() + split.net());
        assertThat(split.ruleVersion()).isEqualTo(1);
    }

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
    void aPaidTripCreditsTheGrossAndDebitsTheCommission() {
        Payment payment = paid(27_000);
        Wallet wallet = Wallet.open(UUID.randomUUID(), payment.getDriverId(), "VND", NOW);

        List<WalletEntry> lines = wallet.creditPayment(payment, TWENTY_PERCENT.split(27_000), UUID::randomUUID, NOW);

        assertThat(lines).extracting(WalletEntry::getEntryType).containsExactly("EARNING", "COMMISSION");
        assertThat(lines).extracting(WalletEntry::getAmount).containsExactly(27_000L, -5_400L);
        assertThat(lines).extracting(WalletEntry::getBalanceAfter).containsExactly(27_000L, 21_600L);
        assertThat(lines).allSatisfy(line -> {
            assertThat(line.getReferenceId()).isEqualTo(payment.getId());
            assertThat(line.getTripId()).isEqualTo(payment.getTripId());
        });
        assertThat(wallet.getBalance()).isEqualTo(21_600);
    }

    @Test
    void aZeroCommissionWritesNoLineAndCurrenciesMustMatch() {
        Payment payment = paid(10_000);
        Wallet wallet = Wallet.open(UUID.randomUUID(), payment.getDriverId(), "VND", NOW);

        List<WalletEntry> lines = wallet.creditPayment(payment, CommissionRule.of(ServiceType.RIDE, 2, "0").split(10_000),
                UUID::randomUUID, NOW);

        assertThat(lines).extracting(WalletEntry::getEntryType).containsExactly("EARNING");
        assertThat(wallet.getBalance()).isEqualTo(10_000);
        Wallet usd = Wallet.open(UUID.randomUUID(), payment.getDriverId(), "USD", NOW);
        assertThatThrownBy(() -> usd.creditPayment(payment, TWENTY_PERCENT.split(10_000), UUID::randomUUID, NOW))
                .isInstanceOf(IllegalArgumentException.class);
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

    @Test
    void adjustmentsAreCompensatingLinesThatNeverTakeTheBalanceBelowZero() {
        Payment payment = paid(27_000);
        Wallet wallet = Wallet.open(UUID.randomUUID(), payment.getDriverId(), "VND", NOW);
        wallet.creditPayment(payment, TWENTY_PERCENT.split(27_000), UUID::randomUUID, NOW);
        UUID actor = UUID.randomUUID();

        Wallet.Adjusted debit = wallet.adjust(UUID.randomUUID(), UUID.randomUUID(), -8_000,
                AdjustmentReason.REFUND_CLAWBACK, null, payment.getTripId(), UUID.randomUUID(), "key-00001", "h", actor,
                NOW);

        assertThat(debit.entry().getEntryType()).isEqualTo("ADJUSTMENT");
        assertThat(debit.entry().getReferenceType()).isEqualTo(Wallet.ADJUSTMENT);
        assertThat(debit.entry().getReferenceId()).isEqualTo(debit.adjustment().getId());
        assertThat(debit.entry().getAmount()).isEqualTo(-8_000);
        assertThat(debit.entry().getBalanceAfter()).isEqualTo(13_600);
        assertThat(debit.adjustment().getRequestedBy()).isEqualTo(actor);
        assertThat(wallet.getBalance()).isEqualTo(13_600);

        assertThatThrownBy(() -> wallet.adjust(UUID.randomUUID(), UUID.randomUUID(), -13_601,
                AdjustmentReason.EARNING_CORRECTION, null, null, null, "key-00002", "h", actor, NOW))
                .isInstanceOf(Wallet.NegativeBalanceException.class);
        assertThat(wallet.getBalance()).isEqualTo(13_600);
        assertThatThrownBy(() -> wallet.adjust(UUID.randomUUID(), UUID.randomUUID(), 0,
                AdjustmentReason.INCENTIVE, null, null, null, "key-00003", "h", actor, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        wallet.adjust(UUID.randomUUID(), UUID.randomUUID(), 5_000, AdjustmentReason.INCENTIVE, null, null, null,
                "key-00004", "h", actor, NOW);
        assertThat(wallet.getBalance()).isEqualTo(18_600);
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
