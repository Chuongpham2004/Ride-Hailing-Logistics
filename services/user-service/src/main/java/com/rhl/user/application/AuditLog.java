package com.rhl.user.application;

import com.rhl.common.web.CorrelationId;
import com.rhl.user.domain.audit.AuditRecord;
import com.rhl.user.infrastructure.persistence.AuditRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/** Writes audit records in the caller's transaction, so an audited change and its record commit together. */
@Component
@RequiredArgsConstructor
public class AuditLog {

    private final AuditRecordRepository records;
    private final Clock clock;


    @Transactional(propagation = Propagation.MANDATORY)
    public void success(UUID actorId, String action, String targetType, Object targetId, Map<String, Object> delta) {
        records.save(AuditRecord.of(actorId, action, targetType, String.valueOf(targetId), "SUCCESS", delta,
                CorrelationId.current(), clock.instant()));
    }
}
