package com.rhl.gateway;

import com.rhl.common.security.JwtClaims;
import com.rhl.common.security.Role;
import com.rhl.common.security.RolesJwtAuthenticationConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Coarse checks at the edge: valid, unrevoked access token and staff role for admin paths.
 * Services repeat authorization and add object-level checks (NFR-SEC-004).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class SecurityConfiguration {

    private static final String REVOKED_PREFIX = "auth:revoked:";

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, JsonErrorWriter errors) {
        return http
                // Bearer tokens only, no cookies or sessions: a cross-site request cannot attach the
                // Authorization header, so CSRF does not apply (CodeQL alerts #2/#3 dismissed for this).
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .authorizeExchange(auth -> auth
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login",
                                "/api/v1/auth/refresh", "/api/v1/auth/password-reset",
                                "/api/v1/auth/password-reset/confirm").permitAll()
                        // Payment provider webhooks carry no user token; payment-service verifies their
                        // HMAC signature, timestamp and event ID instead (FR-PAY).
                        .pathMatchers(HttpMethod.POST, "/api/v1/payments/callbacks/**").permitAll()
                        .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .pathMatchers("/api/v1/admin/**").hasAnyRole(Role.REVIEWER.name(), Role.SUPPORT_STAFF.name(),
                                Role.FINANCE_STAFF.name(), Role.ADMINISTRATOR.name())
                        .pathMatchers("/api/**").authenticated()
                        .anyExchange().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(
                                new ReactiveJwtAuthenticationConverterAdapter(new RolesJwtAuthenticationConverter())))
                        .authenticationEntryPoint(errors.authenticationEntryPoint())
                        .accessDeniedHandler(errors.accessDeniedHandler()))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(errors.authenticationEntryPoint())
                        .accessDeniedHandler(errors.accessDeniedHandler()))
                .build();
    }

    /** Verifies signature (JWKS from user-service), issuer, expiry, token type and revocation. */
    @Bean
    public ReactiveJwtDecoder jwtDecoder(OAuth2ResourceServerProperties properties,
                                         @Value("${rhl.jwt.issuer}") String issuer,
                                         ReactiveStringRedisTemplate redis) {
        NimbusReactiveJwtDecoder nimbus = NimbusReactiveJwtDecoder
                .withJwkSetUri(properties.getJwt().getJwkSetUri())
                .build();
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                jwt -> JwtClaims.ACCESS_TOKEN.equals(jwt.getClaimAsString(JwtClaims.TOKEN_TYPE))
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Not an access token",
                        null))));
        return token -> nimbus.decode(token).flatMap(jwt -> rejectIfRevoked(jwt, redis));
    }

    private static Mono<Jwt> rejectIfRevoked(Jwt jwt, ReactiveStringRedisTemplate redis) {
        if (jwt.getId() == null) {
            return Mono.error(new BadJwtException("Token has no jti"));
        }
        return redis.hasKey(REVOKED_PREFIX + jwt.getId())
                .flatMap(revoked -> revoked
                        ? Mono.<Jwt>error(new BadJwtException("Token revoked"))
                        : Mono.just(jwt));
    }
}
