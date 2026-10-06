package com.rhl.user.application.admin;

import com.rhl.common.security.Role;
import com.rhl.common.web.ApiException;
import com.rhl.user.application.AuditLog;
import com.rhl.user.application.driver.DriverService;
import com.rhl.user.domain.user.User;
import com.rhl.user.domain.user.UserStatus;
import com.rhl.user.infrastructure.persistence.RefreshTokenRepository;
import com.rhl.user.infrastructure.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Role and account-status management (FR-IAM-009, FR-IAM-011). Both revoke the user's refresh
 * tokens so new permissions apply at the next token refresh at the latest.
 */
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final DriverService drivers;
    private final AuditLog audit;
    private final Clock clock;

    @Transactional
    public User replaceRoles(UUID adminId, UUID userId, Set<Role> roles) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        if (adminId.equals(userId) && !roles.contains(Role.ADMINISTRATOR)) {
            throw ApiException.rule("Administrators cannot remove their own administrator role");
        }
        Set<Role> before = EnumSet.copyOf(user.getRoles());
        Instant now = clock.instant();
        user.replaceRoles(roles, now);
        refreshTokens.revokeAllForUser(userId, now);
        audit.success(adminId, "USER_ROLES_CHANGED", "USER", userId,
                Map.of("before", sorted(before), "after", sorted(roles)));
        return user;
    }

    @Transactional
    public User changeStatus(UUID adminId, UUID userId, UserStatus status, String reason) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        if (adminId.equals(userId)) {
            throw ApiException.rule("Administrators cannot change their own account status");
        }
        UserStatus before = user.getStatus();
        Instant now = clock.instant();
        user.changeStatus(status, now);
        if (status != UserStatus.ACTIVE) {
            refreshTokens.revokeAllForUser(userId, now);
            if (user.has(Role.DRIVER)) {
                drivers.forceOffline(userId, "ACCOUNT_" + status.name());
            }
        }
        audit.success(adminId, "USER_STATUS_CHANGED", "USER", userId,
                Map.of("before", before.name(), "after", status.name(), "reason", reason == null ? "" : reason));
        return user;
    }

    private static java.util.List<String> sorted(Set<Role> roles) {
        return roles.stream().map(Enum::name).sorted().toList();
    }
}
