package com.rhl.pricing.infrastructure.persistence;

import com.rhl.pricing.domain.CancellationFeeRule;
import com.rhl.pricing.domain.ServiceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CancellationFeeRuleRepository extends JpaRepository<CancellationFeeRule, UUID> {

    /** The rule in force at {@code at}; the exclusion constraint guarantees there is at most one. */
    @Query("""
            SELECT r FROM CancellationFeeRule r
            WHERE r.serviceType = :serviceType
              AND r.effectiveFrom <= :at AND (r.effectiveTo IS NULL OR r.effectiveTo > :at)
            """)
    Optional<CancellationFeeRule> findEffective(@Param("serviceType") ServiceType serviceType,
                                                @Param("at") Instant at);
}
