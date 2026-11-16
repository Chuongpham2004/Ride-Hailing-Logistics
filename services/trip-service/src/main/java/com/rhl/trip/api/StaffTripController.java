package com.rhl.trip.api;

import com.rhl.common.security.CurrentUser;
import com.rhl.common.web.ApiResponse;
import com.rhl.trip.application.StaffTripService;
import com.rhl.trip.application.TripViews;
import com.rhl.trip.domain.HandoverCode;
import com.rhl.trip.domain.ServiceType;
import com.rhl.trip.domain.TripStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Support staff (README §3: look up trips, see their history, unlock handover codes, cancel by
 * exception). Cancelling goes through {@code POST /api/v1/trips/{id}/cancel}, which staff may use
 * on any trip.
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/trips")
@PreAuthorize("hasAnyRole('SUPPORT_STAFF', 'ADMINISTRATOR')")
public class StaffTripController {

    private final StaffTripService trips;

    /** Newest first; every filter optional, {@code createdTo} exclusive; pass {@code nextBefore} as {@code before}. */
    @GetMapping
    public ApiResponse<TripViews.TripPage> search(@RequestParam(required = false) TripStatus status,
                                                 @RequestParam(required = false) UUID customerId,
                                                 @RequestParam(required = false) UUID driverId,
                                                 @RequestParam(required = false) ServiceType serviceType,
                                                 @RequestParam(required = false) Instant createdFrom,
                                                 @RequestParam(required = false) Instant createdTo,
                                                 @RequestParam(required = false) UUID before,
                                                 @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.ok(trips.search(new StaffTripService.Filter(status, customerId, driverId, serviceType,
                createdFrom, createdTo), before, limit));
    }

    public record CodeUnlockRequest(@NotNull HandoverCode code, @NotBlank @Size(max = 300) String note) {
    }

    /**
     * Lets the driver try a locked pickup or delivery code again (counter back to zero, same
     * code). Only while the code is locked and still needed; the note is required and audited.
     */
    @PostMapping("/{tripId}/code-unlocks")
    public ApiResponse<StaffTripService.TripDetail> unlockCode(@PathVariable UUID tripId,
                                                               @Valid @RequestBody CodeUnlockRequest request) {
        return ApiResponse.ok(trips.unlockCode(CurrentUser.get().id(), tripId, request.code(), request.note()));
    }

    /** The whole trip: route, delivery details, status history, offers and delivery proof. Audited. */
    @GetMapping("/{tripId}")
    public ApiResponse<StaffTripService.TripDetail> detail(@PathVariable UUID tripId) {
        return ApiResponse.ok(trips.detail(CurrentUser.get().id(), tripId));
    }
}
