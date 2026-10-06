package com.rhl.pricing.api;

import com.rhl.common.security.CurrentUser;
import com.rhl.common.web.ApiResponse;
import com.rhl.pricing.application.PricingRuleService;
import com.rhl.pricing.application.PricingViews;
import com.rhl.pricing.domain.ServiceType;
import com.rhl.pricing.domain.Tariff;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** Price list for administrators (README §3). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/pricing/rules")
@PreAuthorize("hasRole('ADMINISTRATOR')")
public class PricingAdminController {

    /** Upper bound per component (VND) against typos such as an extra zero digit. */
    private static final long MAX_AMOUNT = 10_000_000;

    private final PricingRuleService rules;

    public record ScheduleRequest(@NotNull ServiceType serviceType,
                                  @NotBlank @Pattern(regexp = "^[A-Z0-9_]{1,30}$") String regionCode,
                                  @PositiveOrZero @Max(MAX_AMOUNT) long baseFare,
                                  @PositiveOrZero @Max(MAX_AMOUNT) long perKm,
                                  @PositiveOrZero @Max(MAX_AMOUNT) long perMinute,
                                  @PositiveOrZero @Max(MAX_AMOUNT) long minimumFare,
                                  @NotNull Instant effectiveFrom) {
    }

    @GetMapping
    public ApiResponse<List<PricingViews.RuleView>> list() {
        return ApiResponse.ok(rules.list());
    }

    /** Schedules the next version; it takes over from the current one at {@code effectiveFrom}. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PricingViews.RuleView> schedule(@Valid @RequestBody ScheduleRequest r) {
        return ApiResponse.ok(rules.schedule(CurrentUser.get().id(), new PricingRuleService.ScheduleCommand(
                r.serviceType(), r.regionCode(), new Tariff(r.baseFare(), r.perKm(), r.perMinute(), r.minimumFare()),
                r.effectiveFrom())));
    }
}
