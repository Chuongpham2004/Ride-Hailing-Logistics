package com.rhl.pricing.domain;

/** The money part of a pricing rule, in VND (FR-PRI-001). */
public record Tariff(long baseFare, long perKm, long perMinute, long minimumFare) {

    public Tariff {
        if (baseFare < 0 || perKm < 0 || perMinute < 0 || minimumFare < 0) {
            throw DomainException.rule("Prices cannot be negative");
        }
    }
}
