package com.rhl.pricing.application;

import com.rhl.pricing.domain.DomainException;
import com.rhl.pricing.domain.ServiceType;
import com.rhl.pricing.domain.Stop;
import com.rhl.pricing.domain.SurgeAssessment;
import com.rhl.pricing.domain.SurgeRule;
import com.rhl.pricing.infrastructure.cache.SurgeAreas;
import com.rhl.pricing.infrastructure.cache.SurgeCounters;
import com.rhl.pricing.infrastructure.persistence.SurgeRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Surge at the pickup point (FR-PRI-007…012): demand (trip requests) against supply (AVAILABLE
 * drivers with a fresh position) over the pickup's H3 cell and its neighbours, turned into a
 * multiplier by the surge rule in force. When the counters cannot be read the quote is priced
 * at 1.00 and says so: a customer is never charged a surge nobody could compute.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SurgeProvider {

    private final SurgeAreas areas;
    private final SurgeCounters counters;
    private final SurgeRuleRepository rules;

    public SurgeAssessment assess(ServiceType serviceType, Stop pickup, Instant now) {
        String cell = areas.cellOf(pickup.latitude(), pickup.longitude());
        SurgeRule rule = rules.findEffective(serviceType, now)
                .orElseThrow(() -> DomainException.rule("No surge rule is configured for " + serviceType));
        SurgeCounters.Counts counts;
        try {
            counts = counters.count(serviceType, areas.areaAround(cell), now);
        } catch (DataAccessException e) {
            log.warn("Surge counters unavailable, pricing at 1.00: {}", e.getMessage());
            return SurgeAssessment.unavailable(cell);
        }
        return SurgeAssessment.computed(rule, cell, counts.demand(), counts.supply());
    }
}
