package com.rhl.pricing.api;

import com.rhl.common.web.ApiResponse;
import com.rhl.pricing.application.CancellationFeePreview;
import com.rhl.pricing.domain.ServiceType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Service-to-service fee preview for trip-service (FR-CAN). Like the other {@code /internal/**}
 * routes it is never routed by the gateway and must not be exposed outside the service network.
 */
@Validated
@RestController
@RequiredArgsConstructor
public class InternalCancellationFeeController {

    private final CancellationFeePreview previews;

    /**
     * @param actorType  who would cancel: CUSTOMER, DRIVER or STAFF
     * @param status     the trip's current status
     * @param acceptedAt when a driver accepted, if one did
     * @param bookedFare the booked price, which caps the fee
     */
    @GetMapping("/internal/v1/cancellation-fees/preview")
    public ApiResponse<CancellationFeePreview.Preview> preview(
            @RequestParam ServiceType serviceType,
            @RequestParam @Pattern(regexp = "^(CUSTOMER|DRIVER|STAFF)$") String actorType,
            @RequestParam @Pattern(regexp = "^[A-Z_]{1,30}$") String status,
            @RequestParam @Pattern(regexp = "^[A-Z_]{1,40}$") String reason,
            @RequestParam(required = false) Instant acceptedAt,
            @RequestParam(required = false) @Min(0) Long bookedFare) {
        return ApiResponse.ok(previews.preview(serviceType, actorType, status, reason, acceptedAt, bookedFare));
    }
}
