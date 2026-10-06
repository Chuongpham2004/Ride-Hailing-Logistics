package com.rhl.user.infrastructure.persistence;

import com.rhl.user.domain.driver.DocumentType;
import com.rhl.user.domain.driver.DriverDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DriverDocumentRepository extends JpaRepository<DriverDocument, UUID> {

    List<DriverDocument> findByDriverIdAndStatus(UUID driverId, DriverDocument.Status status);

    List<DriverDocument> findByDriverIdAndTypeAndStatus(UUID driverId, DocumentType type, DriverDocument.Status status);
}
