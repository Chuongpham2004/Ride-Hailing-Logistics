package com.rhl.location.api;

import com.rhl.location.application.LocationQueryService;
import com.rhl.location.domain.Availability;
import com.rhl.location.domain.LatestLocation;

import java.time.Instant;
import java.util.UUID;

/** Response bodies shared by the driver and internal APIs. */
public final class LocationViews {

    private LocationViews() {
    }

    public record LocationView(UUID driverId, double latitude, double longitude, double accuracyMeters,
                               Double headingDegrees, Double speedMetersPerSecond, long sequence,
                               Instant deviceTimestamp, Instant serverTimestamp) {

        static LocationView of(LatestLocation l) {
            return new LocationView(l.driverId(), l.latitude(), l.longitude(), l.accuracyMeters(), l.headingDegrees(),
                    l.speedMetersPerSecond(), l.sequence(), l.deviceTime(), l.serverTime());
        }
    }

    /** Distance, freshness, status and vehicle, as FR-LOC asks of a nearby-driver result. */
    public record NearbyDriverView(UUID driverId, long distanceMeters, Availability availability, UUID vehicleId,
                                   long locationAgeMillis, LocationView location) {

        static NearbyDriverView of(LocationQueryService.NearbyDriver d) {
            return new NearbyDriverView(d.driverId(), Math.round(d.distanceMeters()), d.availability(), d.vehicleId(),
                    d.locationAge().toMillis(), LocationView.of(d.location()));
        }
    }
}
