package com.rhl.pricing.infrastructure.persistence;

import com.rhl.pricing.domain.ServiceType;
import com.rhl.pricing.domain.SurgeRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SurgeRuleRepository extends JpaRepository<SurgeRule, UUID> {

    /** The rule in force at {@code at}; the exclusion constraint guarantees there is at most one. */
    @Query("""
            SELECT r FROM SurgeRule r
            WHERE r.serviceType = :serviceType
              AND r.effectiveFrom <= :at AND (r.effectiveTo IS NULL OR r.effectiveTo > :at)
            """)
    Optional<SurgeRule> findEffective(@Param("serviceType") ServiceType serviceType, @Param("at") Instant at);
}
