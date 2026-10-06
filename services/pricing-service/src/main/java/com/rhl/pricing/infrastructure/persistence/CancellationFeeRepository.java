package com.rhl.pricing.infrastructure.persistence;

import com.rhl.pricing.domain.CancellationFee;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CancellationFeeRepository extends JpaRepository<CancellationFee, UUID> {

    boolean existsByTripId(UUID tripId);

    Optional<CancellationFee> findByTripId(UUID tripId);
}
