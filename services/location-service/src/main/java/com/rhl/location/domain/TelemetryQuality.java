package com.rhl.location.domain;

/** How an accepted report is used. Only {@link #CURRENT} moves the driver's latest location. */
public enum TelemetryQuality {
    /** Fresh, accurate and plausible: becomes the latest location. */
    CURRENT,
    /** Sent late after a connection loss: history only, never the current position (FR-LOC). */
    BACKFILL,
    /** Newer sequence but not newer than the stored position's device time. */
    OUT_OF_ORDER,
    /** Accuracy worse than the matching threshold (BR-004). */
    LOW_ACCURACY,
    /** Implies a speed no vehicle can reach since the last position (impossible jump). */
    SUSPICIOUS;

    public boolean isCurrent() {
        return this == CURRENT;
    }
}
