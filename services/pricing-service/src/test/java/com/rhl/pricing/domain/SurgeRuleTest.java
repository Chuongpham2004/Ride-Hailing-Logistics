package com.rhl.pricing.domain;

import com.rhl.pricing.application.SurgeProvider;
import com.rhl.pricing.infrastructure.cache.SurgeAreas;
import com.rhl.pricing.infrastructure.cache.SurgeCounters;
import com.rhl.pricing.infrastructure.persistence.SurgeRuleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The placeholder surge formula seeded by V2__surge.sql (TBD-04). */
class SurgeRuleTest {

    private static final SurgeRule RULE = SurgeRule.of(ServiceType.RIDE, 1, 3, "1.00", "0.50", "2.00", "0.10");

    @ParameterizedTest
    @CsvSource({
            // demand, supply, multiplier
            "2, 0, 1.00",   // below minimum demand: no surge, however few drivers
            "3, 10, 1.00",  // more drivers than requests
            "3, 3, 1.00",   // ratio 1 = threshold
            "4, 2, 1.50",   // ratio 2 -> 1 + 0.5
            "5, 3, 1.30",   // ratio 1.67 -> 1.33, rounded down to the 0.10 step
            "6, 2, 2.00",   // ratio 3 -> 2.00, the cap
            "50, 1, 2.00",  // never above the cap
            "3, 0, 2.00"})  // no supply counts as one driver
    void multiplierFollowsTheRatioWithinTheCap(int demand, int supply, String expected) {
        assertThat(RULE.multiplier(demand, supply)).isEqualTo(expected);
    }

    @Test
    void surgeIsUnavailableNotGuessedWhenCountersCannotBeRead() {
        SurgeAreas areas = mock(SurgeAreas.class);
        SurgeCounters counters = mock(SurgeCounters.class);
        SurgeRuleRepository rules = mock(SurgeRuleRepository.class);
        when(areas.cellOf(10.77, 106.69)).thenReturn("8865b1b6d1fffff");
        when(areas.areaAround("8865b1b6d1fffff")).thenReturn(List.of("8865b1b6d1fffff"));
        when(rules.findEffective(any(), any())).thenReturn(Optional.of(RULE));
        when(counters.count(any(), any(), any())).thenThrow(new RedisConnectionFailureException("down"));

        SurgeAssessment assessment = new SurgeProvider(areas, counters, rules)
                .assess(ServiceType.RIDE, new Stop(10.77, 106.69, "x"), Instant.now());

        assertThat(assessment.source()).isEqualTo(SurgeAssessment.UNAVAILABLE);
        assertThat(assessment.multiplier()).isEqualTo("1.00");
        assertThat(assessment.ruleVersion()).isNull();
        assertThat(assessment.h3Cell()).isEqualTo("8865b1b6d1fffff");
    }

    @Test
    void aNewVersionStartsInTheFutureAndClosesTheCurrentOne() {
        Instant now = Instant.parse("2026-11-16T10:00:00Z");
        SurgeRule.Formula formula = new SurgeRule.Formula(4, new BigDecimal("1.2"), new BigDecimal("0.4"),
                new BigDecimal("1.8"), new BigDecimal("0.05"));
        UUID admin = UUID.randomUUID();

        SurgeRule next = RULE.supersede(UUID.randomUUID(), formula, now.plusSeconds(3600), admin, now);

        assertThat(next.getVersion()).isEqualTo(2);
        assertThat(next.getMaxMultiplier()).isEqualTo("1.80");
        assertThat(next.getCreatedBy()).isEqualTo(admin);
        assertThat(RULE.getEffectiveTo()).isEqualTo(now.plusSeconds(3600));
        assertThat(next.multiplier(50, 1)).isEqualTo("1.80");
        assertThatThrownBy(() -> next.supersede(UUID.randomUUID(), formula, now.minusSeconds(1), admin, now))
                .isInstanceOf(DomainException.class).hasMessageContaining("future");
    }

    @ParameterizedTest
    @CsvSource({
            "0, 1.00, 0.50, 2.00, 0.10, minDemand",
            "3, -0.10, 0.50, 2.00, 0.10, ratioThreshold",
            "3, 1.00, 0.50, 0.90, 0.10, maxMultiplier",
            "3, 1.00, 0.50, 6.00, 0.10, maxMultiplier",
            "3, 1.00, 0.50, 2.00, 0, step",
            "3, 1.00, 0.505, 2.00, 0.10, two decimals"})
    void formulaNumbersStayInRange(int minDemand, String ratio, String slope, String cap, String step,
                                   String message) {
        assertThatThrownBy(() -> new SurgeRule.Formula(minDemand, new BigDecimal(ratio), new BigDecimal(slope),
                new BigDecimal(cap), new BigDecimal(step)))
                .isInstanceOf(DomainException.class).hasMessageContaining(message);
    }
}
