package com.rhl.pricing.infrastructure.security;

import com.rhl.common.security.JwtClaims;
import com.rhl.common.security.RolesJwtAuthenticationConverter;
import com.rhl.common.security.servlet.JsonSecurityErrorHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

/**
 * Stateless bearer-token API, checked here as well as at the gateway (NFR-SEC-004).
 *
 * <p>{@code /internal/**} is for trip-service (README §8.5). The gateway never routes it, so it is
 * reachable only inside the service network. Service credentials for these calls are not
 * specified yet; until they are, the port must not be exposed publicly.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfiguration {

    private static final String REVOKED_PREFIX = "auth:revoked:";

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        JsonSecurityErrorHandler errors = new JsonSecurityErrorHandler(objectMapper);
        return http
                // Stateless API: bearer tokens only, no cookies or sessions, so CSRF does not apply.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/internal/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new RolesJwtAuthenticationConverter()))
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors))
                .exceptionHandling(e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .build();
    }

    /** Verifies signature (JWKS from user-service), issuer, expiry, token type and revocation. */
    @Bean
    public JwtDecoder jwtDecoder(OAuth2ResourceServerProperties properties, @Value("${rhl.jwt.issuer}") String issuer,
                                 StringRedisTemplate redis) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.getJwt().getJwkSetUri()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                accessTokenOnly(),
                notRevoked(redis)));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> accessTokenOnly() {
        return jwt -> JwtClaims.ACCESS_TOKEN.equals(jwt.getClaimAsString(JwtClaims.TOKEN_TYPE))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Not an access token", null));
    }

    private static OAuth2TokenValidator<Jwt> notRevoked(StringRedisTemplate redis) {
        return jwt -> jwt.getId() == null || Boolean.TRUE.equals(redis.hasKey(REVOKED_PREFIX + jwt.getId()))
                ? OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Token revoked", null))
                : OAuth2TokenValidatorResult.success();
    }
}
