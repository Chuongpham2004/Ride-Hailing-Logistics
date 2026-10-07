package com.rhl.pricing.application;

import com.rhl.common.web.ApiException;
import com.rhl.common.web.ErrorCode;
import com.rhl.pricing.domain.CancellationFeeRule;
import com.rhl.pricing.domain.ServiceType;
import com.rhl.pricing.infrastructure.persistence.CancellationFeeRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * What a cancellation would cost if made now (FR-CAN: the fee is shown before the customer
 * confirms). Same rule and same decision as the fee settled from {@code TripCancelled}, but
 * nothing is stored: the settled fee is decided again from the actual cancellation.
 */
@Service
@RequiredArgsConstructor
public class CancellationFeePreview {

    private final CancellationFeeRuleRepository rules;
    private final Clock clock;

    /**
     * @param freeUntil until when a customer cancels for free; {@code null} when that does not
     *                  apply (no driver yet, someone else cancelling, or the window is over)
     */
    public record Preview(String decision, long fee, String currency, int ruleVersion, Instant freeUntil) {
    }

    @Transactional(readOnly = true)
    public Preview preview(ServiceType serviceType, String actorType, String status, String reason,
                           Instant acceptedAt, Long bookedFare) {
        Instant now = clock.instant();
        CancellationFeeRule rule = rules.findEffective(serviceType, now)
                .orElseThrow(() -> new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                        "No cancellation fee rule is in force"));
        CancellationFeeRule.Decision decision = rule.decide(new CancellationFeeRule.Cancellation(
                actorType, status, reason, acceptedAt, now, bookedFare));
        Instant freeUntil = CancellationFeeRule.WITHIN_FREE_WINDOW.equals(decision.decision()) && acceptedAt != null
                ? acceptedAt.plusSeconds(rule.getFreeWindowSeconds()) : null;
        return new Preview(decision.decision(), decision.fee(), rule.getCurrency(), rule.getVersion(), freeUntil);
    }
}
