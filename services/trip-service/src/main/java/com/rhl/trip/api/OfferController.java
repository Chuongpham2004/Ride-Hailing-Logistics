package com.rhl.trip.api;

import com.rhl.common.security.CurrentUser;
import com.rhl.common.web.ApiResponse;
import com.rhl.trip.application.OfferService;
import com.rhl.trip.application.TripViews;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Driver side of dispatch. Offers normally arrive over WebSocket (realtime-gateway); listing them
 * here is the REST fallback (FR-RT). Only the caller's own offers are visible.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/offers")
@PreAuthorize("hasRole('DRIVER')")
public class OfferController {

    private final OfferService offers;

    @GetMapping
    public ApiResponse<List<TripViews.OfferView>> pending() {
        return ApiResponse.ok(offers.pendingFor(CurrentUser.get().id()));
    }

    /** Atomic: one winner per trip; repeating a won accept returns the same trip (COM-008). */
    @PostMapping("/{offerId}/accept")
    public ApiResponse<TripViews.TripView> accept(@PathVariable UUID offerId) {
        return ApiResponse.ok(offers.accept(CurrentUser.get().id(), offerId));
    }

    @PostMapping("/{offerId}/decline")
    public ApiResponse<TripViews.OfferView> decline(@PathVariable UUID offerId) {
        return ApiResponse.ok(offers.decline(CurrentUser.get().id(), offerId));
    }
}
