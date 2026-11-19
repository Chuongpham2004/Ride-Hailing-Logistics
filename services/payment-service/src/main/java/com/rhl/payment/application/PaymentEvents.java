package com.rhl.payment.application;

import com.rhl.common.messaging.OutboxWriter;
import com.rhl.payment.domain.Payment;
import com.rhl.payment.domain.PaymentAttempt;
import com.rhl.payment.domain.Refund;
import com.rhl.payment.infrastructure.messaging.Topics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps payment changes to events in {@code contracts/events/payment}, written to the outbox in
 * the caller's transaction.
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

    /** Once per refund, after the provider confirmed it; {@code refundedTotal} includes it. */
    public void refundCompleted(Payment payment, Refund refund) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("refundId", refund.getId().toString());
        p.put("paymentId", payment.getId().toString());
        p.put("tripId", payment.getTripId().toString());
        p.put("customerId", payment.getCustomerId().toString());
        p.put("driverId", payment.getDriverId() == null ? null : payment.getDriverId().toString());
        p.put("purpose", payment.getPurpose().name());
        p.put("amount", refund.getAmount());
        p.put("currency", refund.getCurrency());
        p.put("refundedTotal", payment.getRefundedAmount());
        p.put("paymentAmount", payment.getAmount());
        p.put("paymentStatus", payment.getStatus().name());
        p.put("reason", refund.getReason().name());
        p.put("provider", refund.getProvider());
        p.put("providerRef", refund.getProviderRef());
        p.put("completedAt", refund.getCompletedAt().toString());
        payment(payment, "RefundCompleted", p);
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
