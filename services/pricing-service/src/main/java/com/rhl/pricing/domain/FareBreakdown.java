package com.rhl.pricing.domain;

import java.math.BigDecimal;

/**
 * Price components shown to the customer (FR-PRI); they always add up to {@code total}.
 *
 * @param minimumFareAdjustment what is added to reach the minimum fare, otherwise 0
 * @param surgeAmount           what the surge multiplier adds, 0 at 1.00
 * @param roundingAdjustment    what rounding the total up to the configured step adds
 */
public record FareBreakdown(long baseFare, long distanceFare, long timeFare, long minimumFareAdjustment,
                            long surgeAmount, long roundingAdjustment, long total, BigDecimal surgeMultiplier) {
}
