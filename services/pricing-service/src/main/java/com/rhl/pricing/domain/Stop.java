package com.rhl.pricing.domain;

import jakarta.persistence.Embeddable;

/** A pickup or drop-off point as snapshotted on the quote (BR-007), WGS84 coordinates. */
@Embeddable
public record Stop(double latitude, double longitude, String address) {
}
