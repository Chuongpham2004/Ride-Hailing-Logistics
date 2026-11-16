package com.rhl.pricing.api;

import com.rhl.common.security.CurrentUser;
import com.rhl.common.web.ApiResponse;
import com.rhl.pricing.application.PricingViews;
import com.rhl.pricing.application.SurgeRuleService;
import com.rhl.pricing.domain.ServiceType;
import com.rhl.pricing.domain.SurgeRule;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Surge rules for administrators (README §3, FR-PRI-007…012). The bounds of each number are
 * checked by {@link SurgeRule.Formula}; a version is scheduled, never edited.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/pricing/surge-rules")
@PreAuthorize("hasRole('ADMINISTRATOR')")
public class SurgeAdminController {

    private final SurgeRuleService rules;

    public record ScheduleRequest(@NotNull ServiceType serviceType, @NotNull Integer minDemand,
                                  @NotNull BigDecimal ratioThreshold, @NotNull BigDecimal slope,
                                  @NotNull BigDecimal maxMultiplier, @NotNull BigDecimal step,
                                  @NotNull Instant effectiveFrom) {
    }

    /** Every version, newest first per service type. */
    @GetMapping
    public ApiResponse<List<PricingViews.SurgeRuleView>> list() {
        return ApiResponse.ok(rules.list());
    }

    /** Schedules the next version; it takes over from the current one at {@code effectiveFrom}. Audited. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PricingViews.SurgeRuleView> schedule(@Valid @RequestBody ScheduleRequest r) {
        SurgeRule.Formula formula = new SurgeRule.Formula(r.minDemand(), r.ratioThreshold(), r.slope(),
                r.maxMultiplier(), r.step());
        return ApiResponse.ok(rules.schedule(CurrentUser.get().id(),
                new SurgeRuleService.ScheduleCommand(r.serviceType(), formula, r.effectiveFrom())));
    }
}
