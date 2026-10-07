package com.rhl.trip.infrastructure.persistence;

import com.rhl.trip.domain.Trip;
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

public interface TripRepository extends JpaRepository<Trip, UUID> {

    /** Lock order is always trip, then offer, so concurrent accept/cancel/dispatch cannot deadlock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Trip t WHERE t.id = :id")
    Optional<Trip> findByIdForUpdate(@Param("id") UUID id);

    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM trips WHERE customer_id = :customerId
                           AND status IN ('CREATED', 'MATCHING', 'ACCEPTED', 'PICKING_UP', 'ARRIVED', 'IN_TRIP'))
            """, nativeQuery = true)
    boolean hasActiveTrip(@Param("customerId") UUID customerId);

    @Query("SELECT COUNT(t) > 0 FROM Trip t WHERE t.fare.quoteId = :quoteId")
    boolean isQuoteUsed(@Param("quoteId") UUID quoteId);

    @Query(value = """
            SELECT driver_id FROM trips
            WHERE driver_id IN (:driverIds) AND status IN ('ACCEPTED', 'PICKING_UP', 'ARRIVED', 'IN_TRIP')
            """, nativeQuery = true)
    Set<UUID> findDriversOnActiveTrips(@Param("driverIds") Collection<UUID> driverIds);

    /** MATCHING trips waiting for their next offer (none open), oldest first. */
    @Query(value = """
            SELECT t.id FROM trips t
            WHERE t.status = 'MATCHING'
              AND NOT EXISTS (SELECT 1 FROM driver_offers o WHERE o.trip_id = t.id AND o.status = 'PENDING')
            ORDER BY t.created_at
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findWaitingForOffer(@Param("limit") int limit);

    // Keyset pagination on the time-ordered UUIDv7 id (README §4.5, NFR-PERF-008).

    @Query(value = "SELECT * FROM trips WHERE customer_id = :userId ORDER BY id DESC LIMIT :limit",
            nativeQuery = true)
    List<Trip> findCustomerPage(@Param("userId") UUID userId, @Param("limit") int limit);

    @Query(value = "SELECT * FROM trips WHERE customer_id = :userId AND id < :before ORDER BY id DESC LIMIT :limit",
            nativeQuery = true)
    List<Trip> findCustomerPage(@Param("userId") UUID userId, @Param("before") UUID before,
                                @Param("limit") int limit);

    @Query(value = "SELECT * FROM trips WHERE driver_id = :userId ORDER BY id DESC LIMIT :limit",
            nativeQuery = true)
    List<Trip> findDriverPage(@Param("userId") UUID userId, @Param("limit") int limit);

    @Query(value = "SELECT * FROM trips WHERE driver_id = :userId AND id < :before ORDER BY id DESC LIMIT :limit",
            nativeQuery = true)
    List<Trip> findDriverPage(@Param("userId") UUID userId, @Param("before") UUID before,
                              @Param("limit") int limit);

    /** Staff search (FR-ADM-001): optional filters, newest first, keyset pagination on the UUIDv7 id. */
    @Query(value = """
            SELECT * FROM trips
            WHERE (CAST(:status AS VARCHAR) IS NULL OR status = CAST(:status AS VARCHAR))
              AND (CAST(:customerId AS UUID) IS NULL OR customer_id = CAST(:customerId AS UUID))
              AND (CAST(:driverId AS UUID) IS NULL OR driver_id = CAST(:driverId AS UUID))
              AND (CAST(:serviceType AS VARCHAR) IS NULL OR service_type = CAST(:serviceType AS VARCHAR))
              AND (CAST(:createdFrom AS TIMESTAMPTZ) IS NULL OR created_at >= CAST(:createdFrom AS TIMESTAMPTZ))
              AND (CAST(:createdTo AS TIMESTAMPTZ) IS NULL OR created_at < CAST(:createdTo AS TIMESTAMPTZ))
              AND (CAST(:before AS UUID) IS NULL OR id < CAST(:before AS UUID))
            ORDER BY id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Trip> search(@Param("status") String status, @Param("customerId") UUID customerId,
                      @Param("driverId") UUID driverId, @Param("serviceType") String serviceType,
                      @Param("createdFrom") Instant createdFrom, @Param("createdTo") Instant createdTo,
                      @Param("before") UUID before, @Param("limit") int limit);
}
