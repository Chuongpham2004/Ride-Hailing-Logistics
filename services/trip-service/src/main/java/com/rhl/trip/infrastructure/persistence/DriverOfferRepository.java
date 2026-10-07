package com.rhl.trip.infrastructure.persistence;

import com.rhl.trip.domain.DriverOffer;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface DriverOfferRepository extends JpaRepository<DriverOffer, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM DriverOffer o WHERE o.id = :id")
    Optional<DriverOffer> findByIdForUpdate(@Param("id") UUID id);

    /** Scalar on purpose: does not put the offer in the persistence context before it is locked. */
    @Query("SELECT o.tripId FROM DriverOffer o WHERE o.id = :id AND o.driverId = :driverId")
    Optional<UUID> findTripIdOfDriverOffer(@Param("id") UUID id, @Param("driverId") UUID driverId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM DriverOffer o WHERE o.tripId = :tripId AND o.status = com.rhl.trip.domain.OfferStatus.PENDING")
    Optional<DriverOffer> findPendingByTripIdForUpdate(@Param("tripId") UUID tripId);

    @Query("SELECT COUNT(o) > 0 FROM DriverOffer o WHERE o.tripId = :tripId AND o.status = com.rhl.trip.domain.OfferStatus.PENDING")
    boolean hasPendingOffer(@Param("tripId") UUID tripId);

    @Query("SELECT o FROM DriverOffer o WHERE o.driverId = :driverId AND o.status = com.rhl.trip.domain.OfferStatus.PENDING")
    List<DriverOffer> findPendingByDriverId(@Param("driverId") UUID driverId);

    @Query("SELECT DISTINCT o.driverId FROM DriverOffer o WHERE o.tripId = :tripId")
    Set<UUID> findOfferedDriverIds(@Param("tripId") UUID tripId);

    @Query("SELECT o.driverId FROM DriverOffer o WHERE o.driverId IN :driverIds AND o.status = com.rhl.trip.domain.OfferStatus.PENDING")
    Set<UUID> findDriversWithPendingOffer(@Param("driverIds") Collection<UUID> driverIds);

    /** Due offers claimed by this instance only; other instances skip the locked rows. */
    @Query(value = """
            SELECT * FROM driver_offers
            WHERE status = 'PENDING' AND expires_at <= :now
            ORDER BY expires_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<DriverOffer> lockExpired(@Param("now") Instant now, @Param("limit") int limit);

    List<DriverOffer> findByTripIdOrderByCreatedAt(UUID tripId);
}
