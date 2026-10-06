package com.rhl.user.application.auth;

import com.rhl.common.id.UuidV7;
import com.rhl.common.security.JwtClaims;
import com.rhl.user.UserServiceProperties;
import com.rhl.user.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/** Signs access tokens and creates opaque refresh tokens. */
@Component
@RequiredArgsConstructor
public class TokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JwtEncoder encoder;
    private final UserServiceProperties properties;

    public record AccessToken(String value, Instant expiresAt) {
    }

    /** Claims carry the user ID and roles only, never PII (README §10.3). */
    public AccessToken issueAccessToken(User user, Instant now) {
        Instant expiresAt = now.plus(properties.jwt().accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.jwt().issuer())
                .subject(user.getId().toString())
                .id(UuidV7.randomString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim(JwtClaims.TOKEN_TYPE, JwtClaims.ACCESS_TOKEN)
                .claim(JwtClaims.ROLES, user.getRoles().stream().map(Enum::name).sorted().toList())
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(properties.jwt().keyId()).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(value, expiresAt);
    }

    /** 256 random bits, URL-safe. */
    public String newRefreshToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public Instant refreshTokenExpiry(Instant now) {
        return now.plus(properties.jwt().refreshTokenTtl());
    }

    public static String hash(String refreshToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(refreshToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
