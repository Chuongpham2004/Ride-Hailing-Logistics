package com.rhl.pricing.infrastructure.persistence;

import com.rhl.pricing.domain.PricingRule;
import com.rhl.pricing.domain.ServiceType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PricingRuleRepository extends JpaRepository<PricingRule, UUID> {

    /** The rule in force at {@code at}; the exclusion constraint guarantees there is at most one. */
    @Query("""
            SELECT r FROM PricingRule r
            WHERE r.serviceType = :serviceType AND r.regionCode = :region
              AND r.effectiveFrom <= :at AND (r.effectiveTo IS NULL OR r.effectiveTo > :at)
            """)
    Optional<PricingRule> findEffective(@Param("serviceType") ServiceType serviceType,
                                        @Param("region") String region, @Param("at") Instant at);

    /** Latest version, locked so two admins cannot both create version n+1. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PricingRule> findFirstByServiceTypeAndRegionCodeOrderByVersionDesc(ServiceType serviceType,
                                                                               String regionCode);

    List<PricingRule> findAllByOrderByServiceTypeAscRegionCodeAscVersionDesc();
}
