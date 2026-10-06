package com.rhl.pricing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Deterministic fare formula (FR-PRI): the same tariff, route, surge and rounding step always
 * give the same breakdown. Intermediate values are exact decimals, never floating point; each
 * component is rounded half-up to whole VND and the total is rounded up to the configured step.
 * The rounding difference is its own component, so the components always add up to the total.
 */
public final class FareCalculator {

    private static final BigDecimal METERS_PER_KM = BigDecimal.valueOf(1000);
    private static final BigDecimal SECONDS_PER_MINUTE = BigDecimal.valueOf(60);

    private FareCalculator() {
    }

    /**
     * @param surgeMultiplier at least 1, two decimals (surge never lowers a price)
     * @param roundingStep    total is rounded up to a multiple of this (VND), at least 1
     */
    public static FareBreakdown calculate(Tariff tariff, RouteEstimate route, BigDecimal surgeMultiplier,
                                          long roundingStep) {
        if (surgeMultiplier.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("Surge multiplier must be at least 1");
        }
        if (roundingStep < 1) {
            throw new IllegalArgumentException("Rounding step must be at least 1");
        }
        long base = tariff.baseFare();
        long distance = vnd(BigDecimal.valueOf(tariff.perKm())
                .multiply(BigDecimal.valueOf(route.distanceMeters()))
                .divide(METERS_PER_KM, 4, RoundingMode.HALF_UP));
        long time = vnd(BigDecimal.valueOf(tariff.perMinute())
                .multiply(BigDecimal.valueOf(route.durationSeconds()))
                .divide(SECONDS_PER_MINUTE, 4, RoundingMode.HALF_UP));

        long metered = base + distance + time;
        long minimumAdjustment = Math.max(0, tariff.minimumFare() - metered);
        long beforeSurge = metered + minimumAdjustment;
        long surged = vnd(BigDecimal.valueOf(beforeSurge).multiply(surgeMultiplier));
        long surgeAmount = surged - beforeSurge;

        long total = Math.ceilDiv(surged, roundingStep) * roundingStep;
        return new FareBreakdown(base, distance, time, minimumAdjustment, surgeAmount, total - surged, total,
                surgeMultiplier.setScale(2, RoundingMode.UNNECESSARY));
    }

    private static long vnd(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
