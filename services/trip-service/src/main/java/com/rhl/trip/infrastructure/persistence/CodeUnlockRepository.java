package com.rhl.trip.infrastructure.persistence;

import com.rhl.trip.domain.CodeUnlock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CodeUnlockRepository extends JpaRepository<CodeUnlock, UUID> {

    List<CodeUnlock> findByTripIdOrderByCreatedAt(UUID tripId);
}
