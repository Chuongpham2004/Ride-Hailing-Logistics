package com.rhl.common.security;

import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.Arrays;
import java.util.function.Predicate;

/**
 * Token checks shared by the services. A person's access token and a service token are signed by
 * the same key, so every service must say which kind it accepts where: user-facing APIs take
 * access tokens only, {@code /internal/**} takes service tokens with the right scope only.
 */
public final class TokenRules {

    private TokenRules() {
    }

    /** Decoder rule for services without an internal API: service tokens are rejected outright. */
    public static OAuth2TokenValidator<Jwt> accessTokenOnly() {
        return jwt -> isType(jwt, JwtClaims.ACCESS_TOKEN)
                ? OAuth2TokenValidatorResult.success()
                : invalid("Not an access token");
    }

    /** Decoder rule for services with an internal API; pair it with {@link #userToken()} and {@link #serviceScope}. */
    public static OAuth2TokenValidator<Jwt> accessOrServiceToken() {
        return jwt -> isType(jwt, JwtClaims.ACCESS_TOKEN) || isType(jwt, JwtClaims.SERVICE_TOKEN)
                ? OAuth2TokenValidatorResult.success()
                : invalid("Unknown token type");
    }

    /** Rejects tokens without an ID or whose ID was revoked (logout, password reset). */
    public static OAuth2TokenValidator<Jwt> notRevoked(Predicate<String> isRevoked) {
        return jwt -> jwt.getId() == null || isRevoked.test(jwt.getId())
                ? invalid("Token revoked")
                : OAuth2TokenValidatorResult.success();
    }

    /**
     * Authorization rule: a person's token (any role); service tokens are refused. The decoder
     * has already limited the token types, so anything that is not a service token is a person's.
     */
    public static AuthorizationManager<RequestAuthorizationContext> userToken() {
        return (authentication, context) -> new AuthorizationDecision(
                tokenOf(authentication.get()) instanceof Jwt jwt && !isType(jwt, JwtClaims.SERVICE_TOKEN));
    }

    /** Authorization rule: a service token that carries {@code scope}. */
    public static AuthorizationManager<RequestAuthorizationContext> serviceScope(String scope) {
        return (authentication, context) -> new AuthorizationDecision(
                tokenOf(authentication.get()) instanceof Jwt jwt && isType(jwt, JwtClaims.SERVICE_TOKEN)
                        && hasScope(jwt, scope));
    }

    static boolean hasScope(Jwt jwt, String scope) {
        String scopes = jwt.getClaimAsString(JwtClaims.SCOPE);
        return scopes != null && Arrays.asList(scopes.split(" ")).contains(scope);
    }

    private static boolean isType(Jwt jwt, String type) {
        return type.equals(jwt.getClaimAsString(JwtClaims.TOKEN_TYPE));
    }

    private static Jwt tokenOf(Authentication authentication) {
        return authentication instanceof JwtAuthenticationToken token ? token.getToken() : null;
    }

    private static OAuth2TokenValidatorResult invalid(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
    }
}
