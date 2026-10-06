package com.rhl.trip.domain;

import java.util.Objects;
import java.util.UUID;

/** @param id the acting user, {@code null} only for {@link ActorType#SYSTEM} */
public record Actor(ActorType type, UUID id) {

    public static final Actor SYSTEM = new Actor(ActorType.SYSTEM, null);

    public Actor {
        Objects.requireNonNull(type, "type");
        if ((type == ActorType.SYSTEM) != (id == null)) {
            throw new IllegalArgumentException("Only the system acts without a user id");
        }
    }

    public static Actor customer(UUID id) {
        return new Actor(ActorType.CUSTOMER, id);
    }

    public static Actor driver(UUID id) {
        return new Actor(ActorType.DRIVER, id);
    }

    public static Actor staff(UUID id) {
        return new Actor(ActorType.STAFF, id);
    }
}
