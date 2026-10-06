package com.rhl.pricing.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The surge applied to one quote and what it was derived from, stored on the quote so the
 * multiplier can be explained and re-derived (FR-PRI, BR-006).
 *
 * @param source {@code COMPUTED} from the counters, or {@code UNAVAILABLE} when they could not be
 *               read; the multiplier is then 1.00 and the other inputs are {@code null}
 */
public record SurgeAssessment(String source, BigDecimal multiplier, String h3Cell, Integer demand, Integer supply,
                              UUID ruleId, Integer ruleVersion) {

    public static final String COMPUTED = "COMPUTED";
    public static final String UNAVAILABLE = "UNAVAILABLE";

    public static SurgeAssessment unavailable(String h3Cell) {
        return new SurgeAssessment(UNAVAILABLE, BigDecimal.ONE.setScale(2), h3Cell, null, null, null, null);
    }

    public static SurgeAssessment computed(SurgeRule rule, String h3Cell, int demand, int supply) {
        return new SurgeAssessment(COMPUTED, rule.multiplier(demand, supply), h3Cell, demand, supply, rule.getId(),
                rule.getVersion());
    }
}
