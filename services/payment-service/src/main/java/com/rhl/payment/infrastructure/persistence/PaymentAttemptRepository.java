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

    /** Attempts whose outcome is still unknown after {@code before}: sent again with the same key. */
    @Query(value = """
            SELECT id FROM payment_attempts
            WHERE status = 'PENDING' AND created_at < :before
            ORDER BY created_at
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findUnresolved(@Param("before") Instant before, @Param("limit") int limit);

    List<PaymentAttempt> findByPaymentIdOrderByAttemptNo(UUID paymentId);

    /** Scalar on purpose: does not load the attempt into the persistence context before the payment is locked. */
    @Query("SELECT a.paymentId FROM PaymentAttempt a WHERE a.id = :id")
    Optional<UUID> findPaymentId(@Param("id") UUID id);
}
