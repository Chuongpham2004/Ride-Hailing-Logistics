package com.rhl.payment.application;

import com.rhl.common.web.CorrelationId;
import com.rhl.payment.domain.AuditRecord;
import com.rhl.payment.infrastructure.persistence.AuditRecordRepository;
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

    /** @param actorId {@code null} when the system acted, e.g. on a provider callback */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID actorId, String action, String targetType, Object targetId, String result,
                       Map<String, Object> delta) {
        records.save(AuditRecord.of(actorId, action, targetType, String.valueOf(targetId), result, delta,
                CorrelationId.current(), clock.instant()));
    }
}
