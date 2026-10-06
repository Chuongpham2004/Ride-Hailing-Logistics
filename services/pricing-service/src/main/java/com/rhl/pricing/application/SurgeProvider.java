package com.rhl.pricing.application;

import com.rhl.pricing.domain.ServiceType;
import com.rhl.pricing.domain.Stop;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Surge multiplier at the pickup point (FR-PRI-007…012). Supply/demand surge by H3 cell comes
 * in the next step; until then every quote is priced at 1.00, which the quote and the trip
 * record explicitly, so nothing changes shape when real surge arrives.
 */
@Component
public class SurgeProvider {

    public static final BigDecimal NONE = new BigDecimal("1.00");

    public BigDecimal multiplier(ServiceType serviceType, Stop pickup) {
        return NONE;
    }
}
