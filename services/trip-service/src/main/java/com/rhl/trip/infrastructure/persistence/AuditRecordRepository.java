package com.rhl.trip.infrastructure.persistence;

import com.rhl.trip.domain.AuditRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AuditRecordRepository extends JpaRepository<AuditRecord, UUID> {
}
