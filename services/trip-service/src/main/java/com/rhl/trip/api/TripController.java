package com.rhl.trip.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rhl.common.security.CurrentUser;
import com.rhl.common.web.ApiResponse;
import com.rhl.trip.application.TripService;
import com.rhl.trip.application.TripViews;
import com.rhl.trip.domain.CancelReason;
import com.rhl.trip.domain.PackageSize;
import com.rhl.trip.domain.TripStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
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

import java.math.BigDecimal;
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

    /**
     * @param quoteId                 from {@code POST /api/v1/quotes}; route, service and price come from it
     * @param acceptedSurgeMultiplier required when the quote has a surge above 1.00, and must equal it (BR-006)
     */
    public record CreateTripRequest(@NotNull UUID quoteId,
                                    @DecimalMin("1.00") @DecimalMax("99.99") BigDecimal acceptedSurgeMultiplier,
                                    @Valid DeliveryRequest delivery) {
    }

    /** Recipient and package of a DELIVERY trip; shown only to the trip's participants and staff. */
    public record DeliveryRequest(@NotBlank @Size(max = 120) String recipientName,
                                  @NotBlank @Size(max = 20) String recipientPhone,
                                  @NotBlank @Size(max = 200) String packageDescription,
                                  @NotNull PackageSize packageSize,
                                  @NotNull @Min(1) Integer packageWeightGrams,
                                  @Size(max = 300) String instructions) {

        TripService.DeliveryCommand toCommand() {
            return new TripService.DeliveryCommand(recipientName, recipientPhone, packageDescription, packageSize,
                    packageWeightGrams, instructions);
        }
    }

    /** What the customer (pickup) or the recipient (delivery) told the driver. */
    public record CodeRequest(@Size(max = 8) String code) {
    }

    public record CancelRequest(@NotNull CancelReason reason, @Size(max = 300) String note) {
    }

    /**
     * Books a quote (UC-03). Requires {@code Idempotency-Key} (COM-008); retries with the same key
     * return the same trip.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<TripViews.TripView> create(
            @RequestHeader(IDEMPOTENCY_KEY) @Pattern(regexp = "^[A-Za-z0-9_-]{8,100}$") String idempotencyKey,
            @Valid @RequestBody CreateTripRequest request) {
        return ApiResponse.ok(trips.create(CurrentUser.get().id(), idempotencyKey, hash(request),
                new TripService.CreateCommand(request.quoteId(), request.acceptedSurgeMultiplier(),
                        request.delivery() == null ? null : request.delivery().toCommand())));
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

    /** Needs {@code {"code"}}, the customer's pickup code, when the trip has one. */
    @PostMapping("/{tripId}/start")
    @PreAuthorize("hasRole('DRIVER')")
    public ApiResponse<TripViews.TripView> start(@PathVariable UUID tripId,
                                                 @Valid @RequestBody(required = false) CodeRequest request) {
        return advance(tripId, TripStatus.IN_TRIP, request);
    }

    /** A delivery needs {@code {"code"}}, the delivery code the recipient gives (proof of delivery). */
    @PostMapping("/{tripId}/complete")
    @PreAuthorize("hasRole('DRIVER')")
    public ApiResponse<TripViews.TripView> complete(@PathVariable UUID tripId,
                                                    @Valid @RequestBody(required = false) CodeRequest request) {
        return advance(tripId, TripStatus.COMPLETED, request);
    }

    private ApiResponse<TripViews.TripView> advance(UUID tripId, TripStatus target) {
        return advance(tripId, target, null);
    }

    private ApiResponse<TripViews.TripView> advance(UUID tripId, TripStatus target, CodeRequest request) {
        return ApiResponse.ok(trips.advance(CurrentUser.get().id(), tripId, target,
                request == null ? null : request.code()));
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
