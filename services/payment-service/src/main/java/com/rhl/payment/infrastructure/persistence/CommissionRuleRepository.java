package com.rhl.payment.infrastructure.persistence;

import com.rhl.payment.domain.CommissionRule;
import com.rhl.payment.domain.ServiceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CommissionRuleRepository extends JpaRepository<CommissionRule, UUID> {

    /** The rule in force at {@code at}; the exclusion constraint guarantees there is at most one. */
    @Query("""
            SELECT r FROM CommissionRule r
            WHERE r.serviceType = :serviceType
              AND r.effectiveFrom <= :at AND (r.effectiveTo IS NULL OR r.effectiveTo > :at)
            """)
    Optional<CommissionRule> findEffective(@Param("serviceType") ServiceType serviceType, @Param("at") Instant at);
}
