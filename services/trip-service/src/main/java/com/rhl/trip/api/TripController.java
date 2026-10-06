package com.rhl.trip.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rhl.common.security.CurrentUser;
import com.rhl.common.web.ApiResponse;
import com.rhl.trip.application.TripService;
import com.rhl.trip.application.TripViews;
import com.rhl.trip.domain.CancelReason;
import com.rhl.trip.domain.ServiceType;
import com.rhl.trip.domain.Stop;
import com.rhl.trip.domain.TripStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/trips")
public class TripController {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final TripService trips;
    private final ObjectMapper objectMapper;

    public record StopRequest(@NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
                              @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
                              @NotBlank @Size(max = 300) String address) {

        Stop toStop() {
            return new Stop(latitude, longitude, address.strip());
        }
    }

    public record CreateTripRequest(@NotNull ServiceType serviceType, @NotNull @Valid StopRequest pickup,
                                    @NotNull @Valid StopRequest dropoff) {
    }

    public record CancelRequest(@NotNull CancelReason reason, @Size(max = 300) String note) {
    }

    /** Requires {@code Idempotency-Key} (COM-008); retries with the same key return the same trip. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<TripViews.TripView> create(
            @RequestHeader(IDEMPOTENCY_KEY) @Pattern(regexp = "^[A-Za-z0-9_-]{8,100}$") String idempotencyKey,
            @Valid @RequestBody CreateTripRequest request) {
        return ApiResponse.ok(trips.create(CurrentUser.get().id(), idempotencyKey, hash(request),
                new TripService.CreateCommand(request.serviceType(), request.pickup().toStop(),
                        request.dropoff().toStop())));
    }

    @GetMapping
    public ApiResponse<TripViews.TripPage> mine(@RequestParam(required = false) UUID before,
                                                @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.ok(trips.mine(CurrentUser.get(), before, limit));
    }

    @GetMapping("/{tripId}")
    public ApiResponse<TripViews.TripView> get(@PathVariable UUID tripId) {
        return ApiResponse.ok(trips.get(CurrentUser.get(), tripId));
    }

    @GetMapping("/{tripId}/history")
    public ApiResponse<List<TripViews.StatusChangeView>> history(@PathVariable UUID tripId) {
        return ApiResponse.ok(trips.history(CurrentUser.get(), tripId));
    }

    /** Customer, assigned driver or support staff; who may cancel in which state follows the state machine. */
    @PostMapping("/{tripId}/cancel")
    public ApiResponse<TripViews.TripView> cancel(@PathVariable UUID tripId, @Valid @RequestBody CancelRequest request) {
        return ApiResponse.ok(trips.cancel(CurrentUser.get(), tripId, request.reason(), request.note()));
    }

    @PostMapping("/{tripId}/start-pickup")
    @PreAuthorize("hasRole('DRIVER')")
    public ApiResponse<TripViews.TripView> startPickup(@PathVariable UUID tripId) {
        return advance(tripId, TripStatus.PICKING_UP);
    }

    @PostMapping("/{tripId}/arrive")
    @PreAuthorize("hasRole('DRIVER')")
    public ApiResponse<TripViews.TripView> arrive(@PathVariable UUID tripId) {
        return advance(tripId, TripStatus.ARRIVED);
    }

    @PostMapping("/{tripId}/start")
    @PreAuthorize("hasRole('DRIVER')")
    public ApiResponse<TripViews.TripView> start(@PathVariable UUID tripId) {
        return advance(tripId, TripStatus.IN_TRIP);
    }

    @PostMapping("/{tripId}/complete")
    @PreAuthorize("hasRole('DRIVER')")
    public ApiResponse<TripViews.TripView> complete(@PathVariable UUID tripId) {
        return advance(tripId, TripStatus.COMPLETED);
    }

    private ApiResponse<TripViews.TripView> advance(UUID tripId, TripStatus target) {
        return ApiResponse.ok(trips.advance(CurrentUser.get().id(), tripId, target));
    }

    /** SHA-256 of the canonical request body, to tell a retry from a different request under the same key. */
    private String hash(CreateTripRequest request) {
        try {
            byte[] body = objectMapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot hash the request", e);
        }
    }
}
