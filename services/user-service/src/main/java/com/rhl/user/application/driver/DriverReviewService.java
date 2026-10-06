package com.rhl.user.application.driver;

import com.rhl.common.web.ApiException;
import com.rhl.user.application.AuditLog;
import com.rhl.user.domain.driver.DocumentRequirements;
import com.rhl.user.domain.driver.DriverDocument;
import com.rhl.user.domain.driver.DriverProfile;
import com.rhl.user.domain.driver.ReviewDecision;
import com.rhl.user.domain.driver.ReviewStatus;
import com.rhl.user.infrastructure.persistence.DriverDocumentRepository;
import com.rhl.user.infrastructure.persistence.DriverProfileRepository;
import com.rhl.user.infrastructure.persistence.ReviewDecisionRepository;
import com.rhl.user.infrastructure.persistence.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Reviewer decisions on driver profiles (UC-01). Every decision is recorded and audited. */
@Service
@RequiredArgsConstructor
public class DriverReviewService {

    public static final int MAX_PAGE_SIZE = 100;

    public enum Verdict { APPROVE, REJECT, REQUEST_CHANGES }

    private final DriverProfileRepository profiles;
    private final VehicleRepository vehicles;
    private final DriverDocumentRepository documents;
    private final ReviewDecisionRepository decisions;
    private final DocumentRequirements requirements;
    private final DriverService drivers;
    private final AuditLog audit;
    private final Clock clock;

    @Transactional(readOnly = true)
    public DriverViews.Page<DriverViews.ReviewQueueItem> queue(ReviewStatus status, UUID after, int size) {
        Limit limit = Limit.of(Math.clamp(size, 1, MAX_PAGE_SIZE));
        List<DriverProfile> page = after == null
                ? profiles.findByReviewStatusOrderByDriverId(status, limit)
                : profiles.findByReviewStatusAndDriverIdGreaterThanOrderByDriverId(status, after, limit);
        String next = page.size() == limit.max() ? page.getLast().getDriverId().toString() : null;
        return new DriverViews.Page<>(page.stream().map(DriverViews.ReviewQueueItem::of).toList(), next);
    }

    /** Full profile including document numbers; reading it is itself audited (README §10.3). */
    @Transactional
    public DriverViews.ReviewView get(UUID reviewerId, UUID driverId) {
        DriverProfile profile = drivers.load(driverId);
        audit.success(reviewerId, "DRIVER_PROFILE_VIEWED", "DRIVER_PROFILE", driverId, Map.of());
        return new DriverViews.ReviewView(drivers.view(profile, true),
                decisions.findByDriverIdOrderByDecidedAt(driverId).stream().map(DriverViews.DecisionView::of).toList());
    }

    /**
     * @param reviewedVersion profile version the reviewer looked at; a mismatch means the profile
     *                        changed in between and the decision is refused
     */
    @Transactional
    public DriverViews.DecisionView decide(UUID reviewerId, UUID driverId, Verdict verdict, int reviewedVersion,
                                           String reason) {
        DriverProfile profile = drivers.load(driverId);
        if (driverId.equals(reviewerId)) {
            throw ApiException.forbidden();
        }
        Instant now = clock.instant();
        ReviewDecision.Decision decision = switch (verdict) {
            case APPROVE -> {
                profile.approve(reviewedVersion, requirements.profileProblems(
                        documents.findByDriverIdAndStatus(driverId, DriverDocument.Status.ACTIVE),
                        vehicles.findByDriverIdOrderByCreatedAt(driverId), today()), now);
                yield ReviewDecision.Decision.APPROVED;
            }
            case REJECT -> {
                profile.reject(reviewedVersion, reason, now);
                yield ReviewDecision.Decision.REJECTED;
            }
            case REQUEST_CHANGES -> {
                profile.requestChanges(reviewedVersion, reason, now);
                yield ReviewDecision.Decision.CHANGES_REQUESTED;
            }
        };
        return record(reviewerId, profile, decision, reason, now);
    }

    @Transactional
    public DriverViews.DecisionView suspend(UUID reviewerId, UUID driverId, String reason) {
        DriverProfile profile = drivers.load(driverId);
        Instant now = clock.instant();
        profile.suspend(reason, now).ifPresent(change -> drivers.publish(profile, change));
        return record(reviewerId, profile, ReviewDecision.Decision.SUSPENDED, reason, now);
    }

    @Transactional
    public DriverViews.DecisionView reinstate(UUID reviewerId, UUID driverId, String reason) {
        DriverProfile profile = drivers.load(driverId);
        Instant now = clock.instant();
        profile.reinstate(now);
        return record(reviewerId, profile, ReviewDecision.Decision.REINSTATED, reason, now);
    }

    private DriverViews.DecisionView record(UUID reviewerId, DriverProfile profile, ReviewDecision.Decision decision,
                                            String reason, Instant now) {
        ReviewDecision saved = decisions.save(ReviewDecision.record(profile, decision, reason, reviewerId, now));
        Map<String, Object> delta = new HashMap<>();
        delta.put("decision", decision.name());
        delta.put("profileVersion", profile.getProfileVersion());
        delta.put("reviewStatus", profile.getReviewStatus().name());
        audit.success(reviewerId, "DRIVER_REVIEW_" + decision.name(), "DRIVER_PROFILE", profile.getDriverId(), delta);
        return DriverViews.DecisionView.of(saved);
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
