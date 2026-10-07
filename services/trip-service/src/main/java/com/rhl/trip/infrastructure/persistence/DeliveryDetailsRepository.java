package com.rhl.trip.infrastructure.persistence;

import com.rhl.trip.domain.DeliveryDetails;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DeliveryDetailsRepository extends JpaRepository<DeliveryDetails, UUID> {
}
