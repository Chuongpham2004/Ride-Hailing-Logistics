package com.rhl.pricing.application;

import com.rhl.pricing.domain.FareBreakdown;
import com.rhl.pricing.domain.FareQuote;
import com.rhl.pricing.domain.PricingRule;
import com.rhl.pricing.domain.ServiceType;
import com.rhl.pricing.domain.Stop;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Response bodies; also the JSON cached in Redis under {@code quote:{id}}. */
public final class PricingViews {

    private PricingViews() {
    }

    /**
     * Everything FR-PRI asks a quote to show: route, components, surge, total, currency, rule
     * versions and expiry. {@code customerId} lets trip-service check ownership (BR-005).
     *
     * @param surgeConfirmationRequired the app must show the surge and get the customer's explicit
     *                                  consent before booking (BR-006)
     */
    public record QuoteView(UUID id, UUID customerId, ServiceType serviceType, Stop pickup, Stop dropoff,
                            int distanceMeters, int durationSeconds, String routeSource, UUID ruleId,
                            int ruleVersion, BigDecimal surgeMultiplier, boolean surgeConfirmationRequired,
                            String surgeSource, Integer surgeRuleVersion, FareBreakdown breakdown, long total,
                            String currency, Instant createdAt, Instant expiresAt) {

        static QuoteView of(FareQuote q) {
            return new QuoteView(q.getId(), q.getCustomerId(), q.getServiceType(), q.getPickup(), q.getDropoff(),
                    q.getDistanceMeters(), q.getDurationSeconds(), q.getRouteSource(), q.getRuleId(),
                    q.getRuleVersion(), q.getSurgeMultiplier(), q.getSurgeMultiplier().compareTo(BigDecimal.ONE) > 0,
                    q.getSurgeSource(), q.getSurgeRuleVersion(), q.breakdown(), q.getTotal(), q.getCurrency(),
                    q.getCreatedAt(), q.getExpiresAt());
        }
    }

    public record RuleView(UUID id, ServiceType serviceType, String regionCode, int version, long baseFare,
                           long perKm, long perMinute, long minimumFare, String currency, Instant effectiveFrom,
                           Instant effectiveTo, Instant createdAt) {

        static RuleView of(PricingRule r) {
            return new RuleView(r.getId(), r.getServiceType(), r.getRegionCode(), r.getVersion(), r.getBaseFare(),
                    r.getPerKm(), r.getPerMinute(), r.getMinimumFare(), r.getCurrency(), r.getEffectiveFrom(),
                    r.getEffectiveTo(), r.getCreatedAt());
        }
    }
}
