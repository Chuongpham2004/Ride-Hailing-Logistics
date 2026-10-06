package com.rhl.location.domain;

/** Driver availability as published by user-service; only {@link #AVAILABLE} drivers are matchable. */
public enum Availability {
    OFFLINE,
    AVAILABLE,
    OFFERED,
    BUSY
}
