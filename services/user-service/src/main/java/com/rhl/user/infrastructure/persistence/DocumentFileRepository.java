package com.rhl.user.infrastructure.persistence;

import com.rhl.user.domain.driver.DocumentFile;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentFileRepository extends JpaRepository<DocumentFile, UUID> {

    /** Locked so two documents submitted at once cannot both attach the same file. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM DocumentFile f WHERE f.id = :id AND f.driverId = :driverId")
    Optional<DocumentFile> findForAttach(@Param("id") UUID id, @Param("driverId") UUID driverId);

    /** Files never attached, uploaded before {@code cutoff}. */
    @Query(value = """
            SELECT id FROM document_files
            WHERE status = 'UPLOADED' AND created_at < :cutoff
            ORDER BY created_at
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findOrphanIds(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    /**
     * Locks one unattached file for deletion; skipped while an attach holds it, so a file a
     * driver is attaching right now is left alone.
     */
    @Query(value = """
            SELECT * FROM document_files
            WHERE id = :id AND status = 'UPLOADED' AND created_at < :cutoff
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<DocumentFile> lockOrphan(@Param("id") UUID id, @Param("cutoff") Instant cutoff);
}
