package com.rhl.location.domain;

/** A report that is malformed, outside any usable time window or from an offline driver; it is not stored. */
public class TelemetryRejectedException extends RuntimeException {

    public enum Reason {
        INVALID_COORDINATES,
        INVALID_ACCURACY,
        FUTURE_TIMESTAMP,
        TOO_OLD,
        DRIVER_OFFLINE
    }

    private final Reason reason;

    public TelemetryRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
