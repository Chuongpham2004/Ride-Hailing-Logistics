package com.rhl.pricing.application;

import com.rhl.common.id.UuidV7;
import com.rhl.pricing.domain.DomainException;
import com.rhl.pricing.domain.PricingRule;
import com.rhl.pricing.domain.ServiceType;
import com.rhl.pricing.domain.Tariff;
import com.rhl.pricing.infrastructure.persistence.PricingRuleRepository;
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
 * Price list administration (README §3: administrators manage prices). Rules are versioned and
 * never edited, so every quote stays reproducible from the rule version it names (FR-PRI); every
 * change is audited with the old and new tariff.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PricingRuleService {

    private final PricingRuleRepository rules;
    private final AuditLog audit;
    private final Clock clock;

    public record ScheduleCommand(ServiceType serviceType, String regionCode, Tariff tariff, Instant effectiveFrom) {
    }

    @Transactional(readOnly = true)
    public List<PricingViews.RuleView> list() {
        return rules.findAllByOrderByServiceTypeAscRegionCodeAscVersionDesc().stream()
                .map(PricingViews.RuleView::of)
                .toList();
    }

    /**
     * Schedules the next version for a service and region; the latest version is closed at
     * {@code effectiveFrom}. Quotes issued before that keep their price.
     */
    @Transactional
    public PricingViews.RuleView schedule(UUID adminId, ScheduleCommand command) {
        Instant now = clock.instant();
        PricingRule latest = rules.findFirstByServiceTypeAndRegionCodeOrderByVersionDesc(command.serviceType(),
                        command.regionCode())
                .orElseThrow(() -> DomainException.rule("No rule exists yet for " + command.serviceType() + " in "
                        + command.regionCode() + "; new regions are added by migration"));
        PricingRule next = latest.supersede(UuidV7.random(), command.tariff(), command.effectiveFrom(), adminId, now);
        rules.saveAndFlush(latest);
        rules.saveAndFlush(next);
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("serviceType", next.getServiceType().name());
        delta.put("regionCode", next.getRegionCode());
        delta.put("version", next.getVersion());
        delta.put("effectiveFrom", next.getEffectiveFrom().toString());
        delta.put("previousVersion", latest.getVersion());
        delta.put("previous", tariff(latest.tariff()));
        delta.put("new", tariff(next.tariff()));
        audit.record(adminId, "PRICING_RULE_SCHEDULED", "PRICING_RULE", next.getId(), "SUCCESS", delta);
        log.info("Pricing rule {} v{} scheduled from {}", next.getServiceType(), next.getVersion(),
                next.getEffectiveFrom());
        return PricingViews.RuleView.of(next);
    }

    private static Map<String, Object> tariff(Tariff t) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("baseFare", t.baseFare());
        values.put("perKm", t.perKm());
        values.put("perMinute", t.perMinute());
        values.put("minimumFare", t.minimumFare());
        return values;
    }
}
