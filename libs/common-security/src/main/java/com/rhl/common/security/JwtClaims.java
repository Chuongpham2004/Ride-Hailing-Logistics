package com.rhl.common.security;

/**
 * Access-token claim names. {@code sub} is the user ID (UUID); tokens never carry PII such as
 * email or phone number.
 */
public final class JwtClaims {

    public static final String ROLES = "roles";
    public static final String TOKEN_TYPE = "typ";
    public static final String ACCESS_TOKEN = "access";
    public static final String ISSUER = "rhl-user-service";

    private JwtClaims() {
    }
}
