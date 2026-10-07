package com.rhl.trip.domain;

import com.rhl.common.id.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * How a delivery was proven (README §6: COMPLETED needs proof for DELIVERY; TBD-08 chose the
 * delivery code). One per trip, append-only.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Immutable
@Table(name = "delivery_proofs")
public class DeliveryProof {

    public static final String DELIVERY_CODE = "DELIVERY_CODE";

    @Id
    private UUID id;

    @Column(name = "trip_id", nullable = false)
    private UUID tripId;

    @Column(nullable = false)
    private String method;

    @Column(name = "driver_id", nullable = false)
    private UUID driverId;

    @Column(name = "verified_at", nullable = false)
    private Instant verifiedAt;

    public static DeliveryProof byCode(UUID tripId, UUID driverId, Instant now) {
        DeliveryProof proof = new DeliveryProof();
        proof.id = UuidV7.random();
        proof.tripId = tripId;
        proof.method = DELIVERY_CODE;
        proof.driverId = driverId;
        proof.verifiedAt = now;
        return proof;
    }
}
