package com.rhl.payment.infrastructure.persistence;

import com.rhl.payment.domain.AuditRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AuditRecordRepository extends JpaRepository<AuditRecord, UUID> {
}
