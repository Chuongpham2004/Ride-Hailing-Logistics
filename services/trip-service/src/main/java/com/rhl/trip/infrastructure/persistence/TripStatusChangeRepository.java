package com.rhl.trip.infrastructure.persistence;

import com.rhl.trip.domain.TripStatusChange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TripStatusChangeRepository extends JpaRepository<TripStatusChange, Long> {

    List<TripStatusChange> findByTripIdOrderByIdAsc(UUID tripId);
}
