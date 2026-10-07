package com.rhl.user.infrastructure.persistence;

import com.rhl.user.domain.driver.DocumentFile;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DocumentFileRepository extends JpaRepository<DocumentFile, UUID> {

    /** Locked so two documents submitted at once cannot both attach the same file. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM DocumentFile f WHERE f.id = :id AND f.driverId = :driverId")
    Optional<DocumentFile> findForAttach(@Param("id") UUID id, @Param("driverId") UUID driverId);
}
