package com.rhl.pricing.api;

import com.rhl.common.web.ApiResponse;
import com.rhl.pricing.application.RouteProvider;
import com.rhl.pricing.domain.RouteEstimate;
import com.rhl.pricing.domain.ServiceType;
import com.rhl.pricing.domain.Stop;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Road route between two points for the apps' maps: the driver's way to the pickup, and the way
 * from the pickup to the drop-off for a driver, who cannot read the customer's quote. Same route
 * source as quotes (OSRM with the straight-line estimate as fallback), so browsers never call a
 * routing server themselves (frontend-design.md §6.1). Display only: prices still come from quotes.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/routes")
public class RouteController {

    private final RouteProvider routes;

    /**
     * @param source   {@code OSRM} for a road route, {@code ESTIMATE} for the straight-line fallback
     * @param polyline encoded polyline, precision 6; absent for the estimate
     */
    public record RouteView(int distanceMeters, int durationSeconds, String source, String polyline) {

        static RouteView of(RouteEstimate route) {
            return new RouteView(route.distanceMeters(), route.durationSeconds(), route.source(), route.polyline());
        }
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('DRIVER', 'CUSTOMER')")
    public ApiResponse<RouteView> route(
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double fromLatitude,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double fromLongitude,
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double toLatitude,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double toLongitude,
            @RequestParam(defaultValue = "RIDE") ServiceType serviceType) {
        Stop from = new Stop(fromLatitude, fromLongitude, "from");
        Stop to = new Stop(toLatitude, toLongitude, "to");
        return ApiResponse.ok(RouteView.of(routes.route(serviceType, from, to)));
    }
}
