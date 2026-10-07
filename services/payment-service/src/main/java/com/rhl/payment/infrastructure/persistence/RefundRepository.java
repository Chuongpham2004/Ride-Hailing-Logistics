package com.rhl.payment.infrastructure.persistence;

import com.rhl.payment.domain.Refund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Refunds only change under their payment's row lock: lock the payment, then read the refund. */
public interface RefundRepository extends JpaRepository<Refund, UUID> {

    /** Refunds whose outcome is unknown, asked again with the same key (see the payment attempt query). */
    @Query(value = """
            SELECT id FROM refunds
            WHERE status = 'PENDING'
              AND ((provider_ref IS NULL AND created_at < :before)
                   OR (provider_ref IS NOT NULL AND created_at < :callbackBefore))
            ORDER BY created_at
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findUnresolved(@Param("before") Instant before, @Param("callbackBefore") Instant callbackBefore,
                              @Param("limit") int limit);

    /** Scalar on purpose: does not load the refund into the persistence context before the payment is locked. */
    @Query("SELECT r.paymentId FROM Refund r WHERE r.id = :id")
    Optional<UUID> findPaymentId(@Param("id") UUID id);

    @Query("SELECT r.id FROM Refund r WHERE r.idempotencyKey = :key AND r.provider = :provider")
    Optional<UUID> findIdByKey(@Param("key") String idempotencyKey, @Param("provider") String provider);

    Optional<Refund> findByPaymentIdAndRequestKey(UUID paymentId, String requestKey);

    List<Refund> findByPaymentIdOrderByCreatedAt(UUID paymentId);
}
