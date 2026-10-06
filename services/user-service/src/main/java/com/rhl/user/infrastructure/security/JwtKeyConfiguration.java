package com.rhl.user.infrastructure.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.rhl.common.security.JwtClaims;
import com.rhl.user.UserServiceProperties;
import com.rhl.user.infrastructure.cache.TokenRevocationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * user-service is the token issuer: it signs access tokens with RS256 and publishes the public
 * key at {@code /.well-known/jwks.json} so the gateway and other services verify tokens without
 * sharing a secret.
 */
@Configuration(proxyBeanMethods = false)
public class JwtKeyConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyConfiguration.class);

    @Bean
    public RSAKey signingKey(UserServiceProperties properties) {
        UserServiceProperties.Jwt jwt = properties.jwt();
        KeyPair pair;
        if (isBlank(jwt.privateKey()) && isBlank(jwt.publicKey())) {
            log.warn("rhl.jwt.private-key is not set: using an ephemeral RSA key. Tokens become invalid on restart "
                    + "and differ between instances. Configure JWT_PRIVATE_KEY/JWT_PUBLIC_KEY outside local dev.");
            pair = generate();
        } else if (isBlank(jwt.privateKey()) || isBlank(jwt.publicKey())) {
            throw new IllegalStateException("Configure both rhl.jwt.private-key and rhl.jwt.public-key");
        } else {
            RSAPrivateKey privateKey = RsaKeyConverters.pkcs8().convert(stream(jwt.privateKey()));
            RSAPublicKey publicKey = RsaKeyConverters.x509().convert(stream(jwt.publicKey()));
            pair = new KeyPair(publicKey, privateKey);
        }
        return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyID(jwt.keyId())
                .build();
    }

    @Bean
    public JWKSet publicJwkSet(RSAKey signingKey) {
        return new JWKSet(signingKey.toPublicJWK());
    }

    @Bean
    public JwtEncoder jwtEncoder(RSAKey signingKey) {
        JWKSource<SecurityContext> source = new ImmutableJWKSet<>(new JWKSet(signingKey));
        return new NimbusJwtEncoder(source);
    }

    @Bean
    public JwtDecoder jwtDecoder(RSAKey signingKey, UserServiceProperties properties,
                                 TokenRevocationStore revocations) throws Exception {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.jwt().issuer()),
                accessTokenOnly(),
                notRevoked(revocations)));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> accessTokenOnly() {
        return jwt -> JwtClaims.ACCESS_TOKEN.equals(jwt.getClaimAsString(JwtClaims.TOKEN_TYPE))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Not an access token", null));
    }

    private static OAuth2TokenValidator<Jwt> notRevoked(TokenRevocationStore revocations) {
        return jwt -> jwt.getId() != null && revocations.isRevoked(jwt.getId())
                ? OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Token revoked", null))
                : OAuth2TokenValidatorResult.success();
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ByteArrayInputStream stream(String pem) {
        return new ByteArrayInputStream(pem.replace("\\n", "\n").getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
