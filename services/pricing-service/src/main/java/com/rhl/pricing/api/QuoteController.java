package com.rhl.pricing.api;

import com.rhl.common.security.CurrentUser;
import com.rhl.common.web.ApiResponse;
import com.rhl.pricing.application.PricingViews;
import com.rhl.pricing.application.QuoteService;
import com.rhl.pricing.domain.ServiceType;
import com.rhl.pricing.domain.Stop;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/quotes")
public class QuoteController {

    private final QuoteService quotes;

    public record StopRequest(@NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
                              @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
                              @NotBlank @Size(max = 300) String address) {

        Stop toStop() {
            return new Stop(latitude, longitude, address.strip());
        }
    }

    public record QuoteRequest(@NotNull ServiceType serviceType, @NotNull @Valid StopRequest pickup,
                               @NotNull @Valid StopRequest dropoff) {
    }

    /** A price valid for a few minutes, bound to the caller (FR-PRI, BR-005). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<PricingViews.QuoteView> create(@Valid @RequestBody QuoteRequest request) {
        return ApiResponse.ok(quotes.create(CurrentUser.get().id(), new QuoteService.QuoteCommand(
                request.serviceType(), request.pickup().toStop(), request.dropoff().toStop())));
    }

    @GetMapping("/{quoteId}")
    public ApiResponse<PricingViews.QuoteView> get(@PathVariable UUID quoteId) {
        return ApiResponse.ok(quotes.get(CurrentUser.get(), quoteId));
    }
}
