package com.rhl.common.security;

/** Minimum RBAC roles (README §3). Authorities are {@code ROLE_<name>}. */
public enum Role {
    CUSTOMER,
    DRIVER,
    REVIEWER,
    SUPPORT_STAFF,
    FINANCE_STAFF,
    ADMINISTRATOR;

    public static final String AUTHORITY_PREFIX = "ROLE_";

    public String authority() {
        return AUTHORITY_PREFIX + name();
    }

    /** Roles a person can pick at sign-up; every other role is granted by an administrator. */
    public boolean isSelfService() {
        return this == CUSTOMER || this == DRIVER;
    }
}
