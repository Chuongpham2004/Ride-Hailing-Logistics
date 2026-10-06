package com.rhl.pricing.infrastructure.persistence;

import com.rhl.pricing.domain.FinalFare;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FinalFareRepository extends JpaRepository<FinalFare, UUID> {

    boolean existsByTripId(UUID tripId);

    Optional<FinalFare> findByTripId(UUID tripId);
}
