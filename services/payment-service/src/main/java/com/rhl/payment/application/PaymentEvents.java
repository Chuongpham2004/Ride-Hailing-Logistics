package com.rhl.payment.application;

import com.rhl.common.messaging.OutboxWriter;
import com.rhl.payment.domain.CommissionRule;
import com.rhl.payment.domain.Payment;
import com.rhl.payment.domain.PaymentAttempt;
import com.rhl.payment.domain.Wallet;
import com.rhl.payment.infrastructure.messaging.Topics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps payment and wallet changes to events in {@code contracts/events/payment} and
 * {@code contracts/events/wallet}, written to the outbox in the caller's transaction.
 */
@Component
@RequiredArgsConstructor
public class PaymentEvents {

    private final OutboxWriter outbox;

    public void succeeded(Payment payment, PaymentAttempt attempt) {
        Map<String, Object> p = base(payment);
        p.put("driverId", payment.getDriverId() == null ? null : payment.getDriverId().toString());
        p.put("provider", attempt.getProvider());
        p.put("providerRef", attempt.getProviderRef());
        p.put("attemptNo", attempt.getAttemptNo());
        p.put("succeededAt", payment.getSucceededAt().toString());
        payment(payment, "PaymentSucceeded", p);
    }

    public void failed(Payment payment, PaymentAttempt attempt) {
        Map<String, Object> p = base(payment);
        p.put("attemptNo", attempt.getAttemptNo());
        p.put("failureCode", attempt.getFailureCode());
        p.put("failedAt", attempt.getCompletedAt().toString());
        payment(payment, "PaymentFailed", p);
    }

    public void earningPosted(Wallet wallet, Payment payment, CommissionRule.Split split, Instant at) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("walletId", wallet.getId().toString());
        p.put("driverId", wallet.getDriverId().toString());
        p.put("tripId", payment.getTripId().toString());
        p.put("paymentId", payment.getId().toString());
        p.put("grossAmount", split.gross());
        p.put("commission", split.commission());
        p.put("netEarning", split.net());
        p.put("currency", wallet.getCurrency());
        p.put("commissionRuleVersion", split.ruleVersion());
        p.put("balance", wallet.getBalance());
        p.put("postedAt", at.toString());
        String driverId = wallet.getDriverId().toString();
        outbox.append(Topics.WALLET_EVENTS, driverId, "DriverEarningPosted", 1, wallet.getId().toString(),
                wallet.getVersion(), p);
    }

    private void payment(Payment payment, String eventType, Map<String, Object> payload) {
        outbox.append(Topics.PAYMENT_EVENTS, payment.getTripId().toString(), eventType, 1,
                payment.getId().toString(), payment.getVersion(), payload);
    }

    private static Map<String, Object> base(Payment payment) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("paymentId", payment.getId().toString());
        p.put("tripId", payment.getTripId().toString());
        p.put("customerId", payment.getCustomerId().toString());
        p.put("purpose", payment.getPurpose().name());
        p.put("amount", payment.getAmount());
        p.put("currency", payment.getCurrency());
        return p;
    }
}
