package com.rhl.payment.infrastructure.persistence;

import com.rhl.payment.domain.Payment;
import com.rhl.payment.domain.PaymentPurpose;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /** Payment first, then attempt, then wallet: one lock order for every writer. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") UUID id);

    boolean existsByTripIdAndPurpose(UUID tripId, PaymentPurpose purpose);

    List<Payment> findByTripIdOrderByCreatedAt(UUID tripId);

    /** Finance listing, newest first, optional status filter; keyset pagination on the UUIDv7 id. */
    @Query(value = """
            SELECT * FROM payments
            WHERE (CAST(:status AS VARCHAR) IS NULL OR status = CAST(:status AS VARCHAR))
              AND (CAST(:before AS UUID) IS NULL OR id < CAST(:before AS UUID))
            ORDER BY id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Payment> findPage(@Param("status") String status, @Param("before") UUID before, @Param("limit") int limit);
}
