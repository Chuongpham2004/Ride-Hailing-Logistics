package com.rhl.pricing.infrastructure.persistence;

import com.rhl.pricing.domain.AuditRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AuditRecordRepository extends JpaRepository<AuditRecord, UUID> {
}
