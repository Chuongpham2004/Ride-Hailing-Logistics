package com.rhl.payment.infrastructure.persistence;

import com.rhl.payment.domain.PaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

    /**
     * Attempts whose outcome is still unknown: sent again with the same key. Attempts the provider
     * accepted wait longer ({@code callbackBefore}) for their callback before being asked again.
     */
    @Query(value = """
            SELECT id FROM payment_attempts
            WHERE status = 'PENDING'
              AND ((provider_ref IS NULL AND created_at < :before)
                   OR (provider_ref IS NOT NULL AND created_at < :callbackBefore))
            ORDER BY created_at
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findUnresolved(@Param("before") Instant before, @Param("callbackBefore") Instant callbackBefore,
                              @Param("limit") int limit);

    /** Scalar on purpose, like {@link #findPaymentId}: the attempt is read only after the payment lock. */
    @Query("SELECT a.id FROM PaymentAttempt a WHERE a.idempotencyKey = :key AND a.provider = :provider")
    Optional<UUID> findIdByKey(@Param("key") String idempotencyKey, @Param("provider") String provider);

    List<PaymentAttempt> findByPaymentIdOrderByAttemptNo(UUID paymentId);

    /** Scalar on purpose: does not load the attempt into the persistence context before the payment is locked. */
    @Query("SELECT a.paymentId FROM PaymentAttempt a WHERE a.id = :id")
    Optional<UUID> findPaymentId(@Param("id") UUID id);
}
