package com.rhl.location.api;

import com.rhl.common.web.ApiException;
import com.rhl.common.web.ApiResponse;
import com.rhl.location.LocationServiceProperties;
import com.rhl.location.application.LocationQueryService;
import com.rhl.location.domain.ServiceType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Service-to-service API for trip-service matching (README §5.1, §8.5). Returns precise
 * positions, so it is never routed through the public gateway (BR-013).
 */
@Validated
@RestController
@RequestMapping("/internal/v1/drivers")
@RequiredArgsConstructor
public class InternalLocationController {

    private final LocationQueryService queries;
    private final LocationServiceProperties properties;

    @GetMapping("/nearby")
    public ApiResponse<List<LocationViews.NearbyDriverView>> nearby(
            @RequestParam @DecimalMin("-85.05112878") @DecimalMax("85.05112878") double latitude,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double longitude,
            @RequestParam ServiceType serviceType,
            @RequestParam(required = false) @Positive Integer radiusMeters,
            @RequestParam(required = false) @Positive Integer limit) {
        LocationServiceProperties.Nearby config = properties.nearby();
        int radius = radiusMeters == null ? config.defaultRadiusMeters() : radiusMeters;
        int size = limit == null ? config.defaultLimit() : limit;
        if (radius > config.maxRadiusMeters() || size > config.maxLimit()) {
            throw ApiException.rule("radiusMeters must be at most " + config.maxRadiusMeters()
                    + " and limit at most " + config.maxLimit());
        }
        return ApiResponse.ok(queries.nearby(serviceType, latitude, longitude, radius, size).stream()
                .map(LocationViews.NearbyDriverView::of)
                .toList());
    }

    @GetMapping("/{driverId}/location")
    public ApiResponse<LocationViews.LocationView> location(@PathVariable UUID driverId) {
        return ApiResponse.ok(queries.current(driverId)
                .map(LocationViews.LocationView::of)
                .orElseThrow(() -> ApiException.notFound("Current location")));
    }
}
