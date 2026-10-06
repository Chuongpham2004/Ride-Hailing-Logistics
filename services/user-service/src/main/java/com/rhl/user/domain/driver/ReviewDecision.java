package com.rhl.user.domain.driver;

import com.rhl.common.id.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/** Append-only record of who decided what on which profile version, and why (FR-DRV, UC-01). */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Immutable
@Table(name = "review_decisions")
public class ReviewDecision {

    public enum Decision { APPROVED, REJECTED, CHANGES_REQUESTED, SUSPENDED, REINSTATED }

    @Id
    private UUID id;

    @Column(name = "driver_id", nullable = false)
    private UUID driverId;

    @Column(name = "profile_version", nullable = false)
    private int profileVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Decision decision;

    private String reason;

    @Column(name = "reviewer_id", nullable = false)
    private UUID reviewerId;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    public static ReviewDecision record(DriverProfile profile, Decision decision, String reason, UUID reviewerId,
                                        Instant now) {
        ReviewDecision d = new ReviewDecision();
        d.id = UuidV7.random();
        d.driverId = profile.getDriverId();
        d.profileVersion = profile.getProfileVersion();
        d.decision = decision;
        d.reason = reason == null || reason.isBlank() ? null : reason.strip();
        d.reviewerId = reviewerId;
        d.decidedAt = now;
        return d;
    }
}
