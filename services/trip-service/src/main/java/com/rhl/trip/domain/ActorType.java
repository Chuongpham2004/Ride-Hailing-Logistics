package com.rhl.trip.domain;

/** Who caused a status change; recorded in the history and in trip events. */
public enum ActorType {
    CUSTOMER,
    DRIVER,
    /** Support staff or an administrator acting through an exception process. */
    STAFF,
    /** The dispatcher itself, e.g. starting matching or giving up after the matching deadline. */
    SYSTEM
}
