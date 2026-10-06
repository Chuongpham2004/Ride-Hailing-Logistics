package com.rhl.user.infrastructure.persistence;

import com.rhl.user.domain.audit.AuditRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AuditRecordRepository extends JpaRepository<AuditRecord, UUID> {
}
