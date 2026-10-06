package com.rhl.user.infrastructure.persistence;

import com.rhl.user.domain.driver.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {

    List<Vehicle> findByDriverIdOrderByCreatedAt(UUID driverId);

    Optional<Vehicle> findByIdAndDriverId(UUID id, UUID driverId);

    boolean existsByPlateNumberAndStatus(String plateNumber, Vehicle.Status status);
}
