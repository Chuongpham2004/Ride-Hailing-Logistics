package com.rhl.common.security;

import com.rhl.common.web.ApiException;
import com.rhl.common.web.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * The authenticated caller as seen by application code. Object-level checks (FR-IAM-010) use
 * {@link #id()} against the resource owner instead of trusting IDs sent by the client.
 */
public record CurrentUser(UUID id, Set<Role> roles) {

    public static CurrentUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
            throw new ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "Authentication is required");
        }
        Set<Role> roles = EnumSet.noneOf(Role.class);
        jwtAuth.getAuthorities().forEach(a -> {
            String name = a.getAuthority();
            if (name.startsWith(Role.AUTHORITY_PREFIX)) {
                roles.add(Role.valueOf(name.substring(Role.AUTHORITY_PREFIX.length())));
            }
        });
        return new CurrentUser(UUID.fromString(jwtAuth.getToken().getSubject()), Set.copyOf(roles));
    }

    public boolean has(Role role) {
        return roles.contains(role);
    }

    public boolean hasAny(Role... candidates) {
        for (Role role : candidates) {
            if (roles.contains(role)) {
                return true;
            }
        }
        return false;
    }

    /** Throws 403 unless the caller is {@code ownerId} or holds one of {@code staffRoles}. */
    public void requireOwnerOr(UUID ownerId, Role... staffRoles) {
        if (!id.equals(ownerId) && !hasAny(staffRoles)) {
            throw ApiException.forbidden();
        }
    }
}
