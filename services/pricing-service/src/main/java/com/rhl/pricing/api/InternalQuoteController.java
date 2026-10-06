package com.rhl.pricing.api;

import com.rhl.common.web.ApiResponse;
import com.rhl.pricing.application.PricingViews;
import com.rhl.pricing.application.QuoteService;
import com.rhl.pricing.domain.ServiceType;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Service-to-service API for trip-service (README §5.1 "validate quote", §8.5). Never routed by
 * the public gateway; like location-service's {@code /internal/**}, it has no service
 * credentials yet, so the port must not be exposed outside the service network.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/quotes")
public class InternalQuoteController {

    private final QuoteService quotes;

    /**
     * 200 with the quote snapshot when it belongs to {@code customerId}, is still valid and
     * matches {@code serviceType} if given; 404 when it is not this customer's; 422
     * {@code QUOTE_EXPIRED} when it ran out.
     */
    @GetMapping("/{quoteId}")
    public ApiResponse<PricingViews.QuoteView> validate(@PathVariable UUID quoteId, @RequestParam UUID customerId,
                                                        @RequestParam(required = false) ServiceType serviceType) {
        return ApiResponse.ok(quotes.validateFor(quoteId, customerId, serviceType));
    }
}
