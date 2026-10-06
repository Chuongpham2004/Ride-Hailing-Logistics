package com.rhl.user.domain.driver;

/**
 * Driver availability (README §5.3). This service decides OFFLINE ↔ AVAILABLE; OFFERED and BUSY
 * are decided atomically by trip-service and only projected here from its events.
 */
public enum Availability {
    OFFLINE,
    AVAILABLE,
    OFFERED,
    BUSY;

    public boolean hasTripInProgress() {
        return this == OFFERED || this == BUSY;
    }
}
