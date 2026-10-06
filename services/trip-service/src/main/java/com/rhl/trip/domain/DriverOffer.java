package com.rhl.trip.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A time-limited proposal of one trip to one driver (FR-MAT). Only a PENDING offer can change. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "driver_offers")
public class DriverOffer {

    @Id
    private UUID id;

    @Column(name = "trip_id", nullable = false)
    private UUID tripId;

    @Column(name = "driver_id", nullable = false)
    private UUID driverId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OfferStatus status;

    @Column(name = "pickup_distance_meters", nullable = false)
    private int pickupDistanceMeters;

    /** {@code null} until persisted, so Spring Data inserts instead of merging. */
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    public static DriverOffer create(UUID id, UUID tripId, DriverCandidate candidate, Duration timeout, Instant now) {
        DriverOffer offer = new DriverOffer();
        offer.id = Objects.requireNonNull(id);
        offer.tripId = Objects.requireNonNull(tripId);
        offer.driverId = candidate.driverId();
        offer.status = OfferStatus.PENDING;
        offer.pickupDistanceMeters = (int) Math.min(Integer.MAX_VALUE, Math.max(0, candidate.distanceMeters()));
        offer.createdAt = now;
        offer.expiresAt = now.plus(timeout);
        return offer;
    }

    /** @throws DomainException {@code OFFER_EXPIRED} unless the offer is still open */
    public void accept(Instant now) {
        requireOpen(now);
        close(OfferStatus.ACCEPTED, now);
    }

    public void decline(Instant now) {
        requireOpen(now);
        close(OfferStatus.DECLINED, now);
    }

    public void expire(Instant now) {
        if (status != OfferStatus.PENDING) {
            throw DomainException.offerExpired("The offer is already " + status);
        }
        close(OfferStatus.EXPIRED, now);
    }

    /** The trip went away (cancelled, no driver) while the offer was open. */
    public void withdraw(Instant now) {
        if (status != OfferStatus.PENDING) {
            throw DomainException.offerExpired("The offer is already " + status);
        }
        close(OfferStatus.CANCELLED, now);
    }

    public boolean isPending() {
        return status == OfferStatus.PENDING;
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    private void requireOpen(Instant now) {
        if (status != OfferStatus.PENDING) {
            throw DomainException.offerExpired("The offer is " + status);
        }
        if (isExpiredAt(now)) {
            throw DomainException.offerExpired("The offer expired at " + expiresAt);
        }
    }

    private void close(OfferStatus target, Instant now) {
        status = target;
        respondedAt = now;
    }
}
