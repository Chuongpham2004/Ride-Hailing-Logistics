package com.rhl.trip.infrastructure.persistence;

import com.rhl.trip.domain.DeliveryProof;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DeliveryProofRepository extends JpaRepository<DeliveryProof, UUID> {

    Optional<DeliveryProof> findByTripId(UUID tripId);
}
