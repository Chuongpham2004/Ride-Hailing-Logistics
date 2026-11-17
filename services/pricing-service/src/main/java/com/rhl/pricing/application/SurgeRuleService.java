package com.rhl.pricing.application;

import com.rhl.common.id.UuidV7;
import com.rhl.pricing.domain.DomainException;
import com.rhl.pricing.domain.ServiceType;
import com.rhl.pricing.domain.SurgeRule;
import com.rhl.pricing.infrastructure.persistence.SurgeRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Surge rule administration (README §3, FR-PRI-007…012): versioned like the price list, never
 * edited, every change audited. Quotes name the surge rule version they were priced with.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SurgeRuleService {

    private final SurgeRuleRepository rules;
    private final AuditLog audit;
    private final Clock clock;

    public record ScheduleCommand(ServiceType serviceType, SurgeRule.Formula formula, Instant effectiveFrom) {
    }

    @Transactional(readOnly = true)
    public List<PricingViews.SurgeRuleView> list() {
        return rules.findAllByOrderByServiceTypeAscVersionDesc().stream()
                .map(PricingViews.SurgeRuleView::of)
                .toList();
    }

    /** Schedules the next version for a service type; the latest version is closed at {@code effectiveFrom}. */
    @Transactional
    public PricingViews.SurgeRuleView schedule(UUID adminId, ScheduleCommand command) {
        Instant now = clock.instant();
        SurgeRule latest = rules.findFirstByServiceTypeOrderByVersionDesc(command.serviceType())
                .orElseThrow(() -> DomainException.rule("No surge rule exists yet for " + command.serviceType()));
        SurgeRule next = latest.supersede(UuidV7.random(), command.formula(), command.effectiveFrom(), adminId, now);
        rules.saveAndFlush(latest);
        rules.saveAndFlush(next);
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("serviceType", next.getServiceType().name());
        delta.put("version", next.getVersion());
        delta.put("effectiveFrom", next.getEffectiveFrom().toString());
        delta.put("previousVersion", latest.getVersion());
        delta.put("previous", formula(latest.formula()));
        delta.put("new", formula(next.formula()));
        audit.record(adminId, "SURGE_RULE_SCHEDULED", "SURGE_RULE", next.getId(), "SUCCESS", delta);
        log.info("Surge rule {} v{} scheduled from {}", next.getServiceType(), next.getVersion(),
                next.getEffectiveFrom());
        return PricingViews.SurgeRuleView.of(next);
    }

    private static Map<String, Object> formula(SurgeRule.Formula f) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("minDemand", f.minDemand());
        values.put("ratioThreshold", f.ratioThreshold().toPlainString());
        values.put("slope", f.slope().toPlainString());
        values.put("maxMultiplier", f.maxMultiplier().toPlainString());
        values.put("step", f.step().toPlainString());
        return values;
    }
}
