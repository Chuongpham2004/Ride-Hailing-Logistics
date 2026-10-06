package com.rhl.location.api;

import com.rhl.common.security.CurrentUser;
import com.rhl.common.web.ApiException;
import com.rhl.common.web.ApiResponse;
import com.rhl.location.application.LocationQueryService;
import com.rhl.location.application.TelemetryService;
import com.rhl.location.domain.TelemetryReport;
import com.rhl.location.infrastructure.persistence.TelemetryHistoryRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Driver-facing HTTP API. Drivers normally stream positions over WebSocket through
 * realtime-gateway; this endpoint is the HTTP fallback and runs the same pipeline.
 */
@RestController
@RequestMapping("/api/v1/locations/me")
@RequiredArgsConstructor
@PreAuthorize("hasRole('DRIVER')")
public class DriverLocationController {

    private final TelemetryService telemetry;
    private final LocationQueryService queries;

    /** Same fields as the WebSocket DRIVER_LOCATION_UPDATED message (README §8.4); no driverId. */
    public record LocationReportRequest(
            @NotNull @PositiveOrZero Long sequence,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @NotNull @PositiveOrZero Double accuracyMeters,
            @DecimalMin("0") @DecimalMax("360") Double headingDegrees,
            @PositiveOrZero Double speedMetersPerSecond,
            @NotNull Instant deviceTimestamp) {
    }

    public record ReportResult(long sequence, TelemetryService.Outcome outcome) {
    }

    @PostMapping
    public ApiResponse<ReportResult> report(@Valid @RequestBody LocationReportRequest r) {
        TelemetryReport report = new TelemetryReport(CurrentUser.get().id(), r.sequence(), r.latitude(),
                r.longitude(), r.accuracyMeters(), r.headingDegrees(), r.speedMetersPerSecond(), r.deviceTimestamp());
        return ApiResponse.ok(new ReportResult(r.sequence(),
                telemetry.ingest(report, TelemetryHistoryRepository.Source.HTTP)));
    }

    @GetMapping
    public ApiResponse<LocationViews.LocationView> current() {
        return ApiResponse.ok(queries.current(CurrentUser.get().id())
                .map(LocationViews.LocationView::of)
                .orElseThrow(() -> ApiException.notFound("Current location")));
    }
}
