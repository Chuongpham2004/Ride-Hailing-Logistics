package com.rhl.common.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Maps the {@code roles} claim to {@code ROLE_*} authorities; unknown role names are dropped. */
public class RolesJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        return new JwtAuthenticationToken(jwt, authorities(jwt), jwt.getSubject());
    }

    public static Collection<GrantedAuthority> authorities(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(JwtClaims.ROLES);
        if (roles == null) {
            return List.of();
        }
        return roles.stream()
                .map(RolesJwtAuthenticationConverter::parse)
                .filter(Objects::nonNull)
                .<GrantedAuthority>map(r -> new SimpleGrantedAuthority(r.authority()))
                .toList();
    }

    private static Role parse(String name) {
        try {
            return Role.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
