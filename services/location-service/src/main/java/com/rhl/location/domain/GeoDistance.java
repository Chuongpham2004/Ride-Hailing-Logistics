package com.rhl.location.domain;

/** Great-circle distance on the WGS84 mean radius; accurate enough for plausibility checks. */
public final class GeoDistance {

    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private GeoDistance() {
    }

    public static double meters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
