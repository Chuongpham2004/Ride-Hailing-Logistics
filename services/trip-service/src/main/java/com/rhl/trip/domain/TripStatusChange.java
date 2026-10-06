package com.rhl.trip.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/** One row of the append-only trip_status_history (README §6, DR-005). */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Immutable
@Table(name = "trip_status_history")
public class TripStatusChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trip_id", nullable = false)
    private UUID tripId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private TripStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private TripStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false)
    private ActorType actorType;

    @Column(name = "actor_id")
    private UUID actorId;

    private String reason;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public static TripStatusChange of(Transition transition) {
        TripStatusChange change = new TripStatusChange();
        change.tripId = transition.tripId();
        change.fromStatus = transition.from();
        change.toStatus = transition.to();
        change.actorType = transition.actor().type();
        change.actorId = transition.actor().id();
        change.reason = transition.reason();
        change.occurredAt = transition.at();
        return change;
    }
}
