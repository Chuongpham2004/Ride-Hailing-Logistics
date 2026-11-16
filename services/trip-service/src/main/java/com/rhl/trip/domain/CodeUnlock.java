package com.rhl.trip.domain;

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

/** Support unlocked a handover code of a trip, and why (README §6). Append-only. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Immutable
@Table(name = "handover_code_unlocks")
public class CodeUnlock {

    @Id
    private UUID id;

    @Column(name = "trip_id", nullable = false)
    private UUID tripId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HandoverCode code;

    @Column(name = "staff_id", nullable = false)
    private UUID staffId;

    @Column(name = "failures_before", nullable = false)
    private int failuresBefore;

    @Column(nullable = false)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static CodeUnlock record(UUID tripId, HandoverCode code, UUID staffId, int failuresBefore, String note,
                                    Instant now) {
        CodeUnlock unlock = new CodeUnlock();
        unlock.id = UuidV7.random();
        unlock.tripId = tripId;
        unlock.code = code;
        unlock.staffId = staffId;
        unlock.failuresBefore = failuresBefore;
        unlock.note = note;
        unlock.createdAt = now;
        return unlock;
    }
}
