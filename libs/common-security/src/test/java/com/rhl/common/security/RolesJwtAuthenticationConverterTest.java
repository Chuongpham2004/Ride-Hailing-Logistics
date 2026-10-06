package com.rhl.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RolesJwtAuthenticationConverterTest {

    @Test
    void mapsKnownRolesAndDropsUnknownOnes() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("0199b3a8-5c2e-7a41-9b6d-2f7e8c1d4a12")
                .claim(JwtClaims.ROLES, List.of("DRIVER", "SUPERUSER"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        var token = new RolesJwtAuthenticationConverter().convert(jwt);

        assertThat(token.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_DRIVER");
        assertThat(token.getName()).isEqualTo("0199b3a8-5c2e-7a41-9b6d-2f7e8c1d4a12");
    }

    @Test
    void tokenWithoutRolesHasNoAuthorities() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("x").build();

        assertThat(RolesJwtAuthenticationConverter.authorities(jwt)).isEmpty();
    }
}
