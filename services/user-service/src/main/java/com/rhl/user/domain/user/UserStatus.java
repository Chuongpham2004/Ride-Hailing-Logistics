package com.rhl.user.domain.user;

public enum UserStatus {
    ACTIVE,
    /** Temporarily blocked by staff; can be reactivated. */
    LOCKED,
    DISABLED
}
