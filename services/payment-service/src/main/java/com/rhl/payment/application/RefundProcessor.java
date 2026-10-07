package com.rhl.payment.application;

import com.rhl.payment.PaymentServiceProperties;
import com.rhl.payment.domain.RefundReason;
import com.rhl.payment.infrastructure.persistence.RefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Sends refunds to the provider and records the answer, like {@link PaymentProcessor} does for
 * charges: an unknown outcome leaves the refund PENDING and it is sent again later with the same
 * idempotency key, so a customer is refunded at most once per refund.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundProcessor {

    private final RefundSteps steps;
    private final PaymentQueries queries;
    private final PaymentProvider provider;
    private final RefundRepository refunds;
    private final PaymentServiceProperties properties;
    private final Clock clock;

    /** Opens the refund and sends it right away; the outcome so far is on the returned refund. */
    public PaymentQueries.RefundView refund(UUID actorId, UUID paymentId, Long amount, RefundReason reason,
                                            String note, String requestKey, String requestHash) {
        RefundSteps.Requested requested = steps.request(actorId, paymentId, amount, reason, note, requestKey,
                requestHash);
        if (requested.created()) {
            send(requested.refundId());
        }
        return queries.refund(requested.refundId());
    }

    public void send(UUID refundId) {
        steps.providerRequest(refundId).ifPresent(request -> {
            PaymentProvider.ChargeResult result;
            try {
                result = provider.refund(request);
            } catch (PaymentProvider.ProviderUnavailableException e) {
                log.warn("Refund outcome unknown for {}, will retry: {}", refundId, e.getMessage());
                return;
            }
            steps.complete(refundId, result);
        });
    }

    @Scheduled(fixedDelayString = "${rhl.charge.resolve-every}")
    public void resolveUnknownOutcomes() {
        PaymentServiceProperties.Charge config = properties.charge();
        try {
            Instant now = clock.instant();
            for (UUID refundId : refunds.findUnresolved(now.minus(config.resolveAfter()),
                    now.minus(config.callbackTimeout()), config.resolveBatch())) {
                try {
                    send(refundId);
                } catch (RuntimeException e) {
                    log.error("Could not resolve refund {}", refundId, e);
                }
            }
        } catch (DataAccessException e) {
            log.warn("Unresolved refund scan failed: {}", e.getMostSpecificCause().getMessage());
        }
    }
}
