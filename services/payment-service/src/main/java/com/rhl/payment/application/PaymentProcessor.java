package com.rhl.payment.application;

import com.rhl.payment.PaymentServiceProperties;
import com.rhl.payment.infrastructure.persistence.PaymentAttemptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/**
 * Sends attempts to the provider and records the answer. When the outcome is unknown (provider
 * unreachable, crash after the call), the attempt stays PENDING and is sent again later with the
 * same idempotency key, so a payment is charged at most once (FR-PAY, BR-015).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentProcessor {

    private final PaymentSteps steps;
    private final PaymentProvider provider;
    private final PaymentAttemptRepository attempts;
    private final PaymentServiceProperties properties;
    private final Clock clock;

    public void charge(UUID attemptId) {
        steps.request(attemptId).ifPresent(request -> {
            PaymentProvider.ChargeResult result;
            try {
                result = provider.charge(request);
            } catch (PaymentProvider.ProviderUnavailableException e) {
                log.warn("Charge outcome unknown for attempt {}, will retry: {}", attemptId, e.getMessage());
                return;
            }
            steps.complete(attemptId, result);
        });
    }

    @Scheduled(fixedDelayString = "${rhl.charge.resolve-every}")
    public void resolveUnknownOutcomes() {
        PaymentServiceProperties.Charge config = properties.charge();
        try {
            for (UUID attemptId : attempts.findUnresolved(clock.instant().minus(config.resolveAfter()),
                    config.resolveBatch())) {
                try {
                    charge(attemptId);
                } catch (RuntimeException e) {
                    log.error("Could not resolve attempt {}", attemptId, e);
                }
            }
        } catch (DataAccessException e) {
            log.warn("Unresolved attempt scan failed: {}", e.getMostSpecificCause().getMessage());
        }
    }
}
