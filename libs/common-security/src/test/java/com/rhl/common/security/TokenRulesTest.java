package com.rhl.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TokenRulesTest {

    private static final Jwt USER = jwt(JwtClaims.ACCESS_TOKEN, null);
    private static final Jwt SERVICE = jwt(JwtClaims.SERVICE_TOKEN,
            ServiceScopes.INTERNAL_LOCATION + " " + ServiceScopes.INTERNAL_PRICING);

    @Test
    void userApisTakeAccessTokensOnly() {
        assertThat(allowed(TokenRules.userToken(), USER)).isTrue();
        assertThat(allowed(TokenRules.userToken(), SERVICE)).isFalse();
        assertThat(TokenRules.userToken()
                .authorize(() -> new TestingAuthenticationToken("x", "y"), null).isGranted()).isFalse();
    }

    @Test
    void internalApisTakeServiceTokensWithTheScope() {
        assertThat(allowed(TokenRules.serviceScope(ServiceScopes.INTERNAL_PRICING), SERVICE)).isTrue();
        assertThat(allowed(TokenRules.serviceScope("internal:payment"), SERVICE)).isFalse();
        assertThat(allowed(TokenRules.serviceScope(ServiceScopes.INTERNAL_PRICING), USER)).isFalse();
        // A person's token cannot smuggle a scope in.
        Jwt userWithScope = jwt(JwtClaims.ACCESS_TOKEN, ServiceScopes.INTERNAL_PRICING);
        assertThat(allowed(TokenRules.serviceScope(ServiceScopes.INTERNAL_PRICING), userWithScope)).isFalse();
    }

    @Test
    void scopesMatchWholeWordsOnly() {
        assertThat(TokenRules.hasScope(jwt(JwtClaims.SERVICE_TOKEN, "internal:locations"),
                ServiceScopes.INTERNAL_LOCATION)).isFalse();
    }

    @Test
    void decoderRulesPerTokenType() {
        assertThat(TokenRules.accessTokenOnly().validate(USER).hasErrors()).isFalse();
        assertThat(TokenRules.accessTokenOnly().validate(SERVICE).hasErrors()).isTrue();
        assertThat(TokenRules.accessOrServiceToken().validate(SERVICE).hasErrors()).isFalse();
        assertThat(TokenRules.accessOrServiceToken().validate(jwt("refresh", null)).hasErrors()).isTrue();
        assertThat(TokenRules.notRevoked(id -> true).validate(USER).hasErrors()).isTrue();
        assertThat(TokenRules.notRevoked(id -> false).validate(USER).hasErrors()).isFalse();
    }

    private static boolean allowed(AuthorizationManager<RequestAuthorizationContext> rule, Jwt jwt) {
        Authentication authentication = new RolesJwtAuthenticationConverter().convert(jwt);
        return rule.authorize(() -> authentication, null).isGranted();
    }

    private static Jwt jwt(String type, String scope) {
        Jwt.Builder builder = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .jti("id-1")
                .subject("trip-service")
                .claim(JwtClaims.TOKEN_TYPE, type)
                .claim(JwtClaims.ROLES, List.of())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        if (scope != null) {
            builder.claim(JwtClaims.SCOPE, scope);
        }
        return builder.build();
    }
}
