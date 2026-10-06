package com.rhl.payment.infrastructure.provider;

import com.rhl.payment.application.PaymentProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stand-in for the real provider until TBD-09 is decided (README §2: one electronic method in
 * sandbox). Approves every charge and honours idempotency keys like a real provider would, so
 * the retry paths behave the same. Keys live in memory: development only.
 */
@Component
public class SandboxPaymentProvider implements PaymentProvider {

    public static final String NAME = "SANDBOX";

    private final Map<String, ChargeResult> charges = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ChargeResult charge(ChargeRequest request) {
        return charges.computeIfAbsent(request.idempotencyKey(),
                key -> ChargeResult.success("sbx_" + UUID.randomUUID().toString().replace("-", "")));
    }
}
