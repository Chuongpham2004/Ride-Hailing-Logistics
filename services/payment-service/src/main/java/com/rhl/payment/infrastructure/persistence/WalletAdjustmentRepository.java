package com.rhl.payment.infrastructure.persistence;

import com.rhl.payment.domain.AdjustmentReason;
import com.rhl.payment.domain.WalletAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface WalletAdjustmentRepository extends JpaRepository<WalletAdjustment, UUID> {

    Optional<WalletAdjustment> findByWalletIdAndRequestKey(UUID walletId, String requestKey);

    /** What was already taken back from the driver for a refund; zero or negative. */
    @Query("""
            SELECT COALESCE(SUM(a.amount), 0) FROM WalletAdjustment a
            WHERE a.refundId = :refundId AND a.reason = :reason
            """)
    long sumByRefund(@Param("refundId") UUID refundId, @Param("reason") AdjustmentReason reason);
}
