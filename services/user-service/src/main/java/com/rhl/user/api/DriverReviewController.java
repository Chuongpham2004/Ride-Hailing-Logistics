package com.rhl.user.api;

import com.rhl.common.security.CurrentUser;
import com.rhl.common.web.ApiResponse;
import com.rhl.user.application.driver.DriverReviewService;
import com.rhl.user.application.driver.DriverViews;
import com.rhl.user.domain.driver.ReviewStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Driver review back office (UC-01). Reviewers and administrators only. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/drivers")
@PreAuthorize("hasAnyRole('REVIEWER', 'ADMINISTRATOR')")
public class DriverReviewController {

    private final DriverReviewService reviews;

    public record DecisionRequest(@NotNull DriverReviewService.Verdict verdict, @Min(1) int profileVersion,
                                  @Size(max = 500) String reason) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }

    @GetMapping
    public ApiResponse<DriverViews.Page<DriverViews.ReviewQueueItem>> queue(
            @RequestParam(defaultValue = "PENDING_REVIEW") ReviewStatus status,
            @RequestParam(required = false) UUID after,
            @RequestParam(defaultValue = "20") @Min(1) @Max(DriverReviewService.MAX_PAGE_SIZE) int size) {
        return ApiResponse.ok(reviews.queue(status, after, size));
    }

    @GetMapping("/{driverId}")
    public ApiResponse<DriverViews.ReviewView> get(@PathVariable UUID driverId) {
        return ApiResponse.ok(reviews.get(CurrentUser.get().id(), driverId));
    }

    @PostMapping("/{driverId}/decisions")
    public ApiResponse<DriverViews.DecisionView> decide(@PathVariable UUID driverId,
                                                        @Valid @RequestBody DecisionRequest request) {
        return ApiResponse.ok(reviews.decide(CurrentUser.get().id(), driverId, request.verdict(),
                request.profileVersion(), request.reason()));
    }

    @PostMapping("/{driverId}/suspend")
    public ApiResponse<DriverViews.DecisionView> suspend(@PathVariable UUID driverId,
                                                         @Valid @RequestBody ReasonRequest request) {
        return ApiResponse.ok(reviews.suspend(CurrentUser.get().id(), driverId, request.reason()));
    }

    @PostMapping("/{driverId}/reinstate")
    public ApiResponse<DriverViews.DecisionView> reinstate(@PathVariable UUID driverId,
                                                           @Valid @RequestBody ReasonRequest request) {
        return ApiResponse.ok(reviews.reinstate(CurrentUser.get().id(), driverId, request.reason()));
    }
}
