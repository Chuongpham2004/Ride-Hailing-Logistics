package com.rhl.common.security;

/**
 * Token claim names. {@code sub} is the user ID (UUID) in an access token and the client ID
 * (for example {@code trip-service}) in a service token; tokens never carry PII such as email or
 * phone number.
 */
public final class JwtClaims {

    public static final String ROLES = "roles";
    public static final String TOKEN_TYPE = "typ";
    /** A person's token, issued at login or refresh. */
    public static final String ACCESS_TOKEN = "access";
    /** A service's token for {@code /internal/**} calls, issued by the client credentials grant. */
    public static final String SERVICE_TOKEN = "service";
    /** Space-separated scopes of a service token (RFC 9068), see {@link ServiceScopes}. */
    public static final String SCOPE = "scope";
    public static final String ISSUER = "rhl-user-service";

    private JwtClaims() {
    }
}
