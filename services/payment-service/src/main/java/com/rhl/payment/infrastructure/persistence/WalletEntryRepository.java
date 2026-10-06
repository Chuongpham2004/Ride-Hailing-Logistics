package com.rhl.payment.infrastructure.persistence;

import com.rhl.payment.domain.WalletEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface WalletEntryRepository extends JpaRepository<WalletEntry, UUID> {

    // Keyset pagination on the time-ordered UUIDv7 id (NFR-PERF-008).

    @Query(value = "SELECT * FROM wallet_entries WHERE wallet_id = :walletId ORDER BY id DESC LIMIT :limit",
            nativeQuery = true)
    List<WalletEntry> findPage(@Param("walletId") UUID walletId, @Param("limit") int limit);

    @Query(value = """
            SELECT * FROM wallet_entries WHERE wallet_id = :walletId AND id < :before
            ORDER BY id DESC LIMIT :limit
            """, nativeQuery = true)
    List<WalletEntry> findPage(@Param("walletId") UUID walletId, @Param("before") UUID before,
                               @Param("limit") int limit);
}
