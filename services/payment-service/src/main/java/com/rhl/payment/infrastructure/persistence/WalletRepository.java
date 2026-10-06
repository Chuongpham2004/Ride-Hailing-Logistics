package com.rhl.payment.infrastructure.persistence;

import com.rhl.payment.domain.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    /** Balance changes are serialised per wallet (FR-WAL-009). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.driverId = :driverId AND w.currency = :currency")
    Optional<Wallet> findForUpdate(@Param("driverId") UUID driverId, @Param("currency") String currency);

    Optional<Wallet> findByDriverIdAndCurrency(UUID driverId, String currency);
}
