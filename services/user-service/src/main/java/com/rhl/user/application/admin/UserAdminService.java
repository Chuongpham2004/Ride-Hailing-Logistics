package com.rhl.user.application.admin;

import com.rhl.common.security.Role;
import com.rhl.common.web.ApiException;
import com.rhl.user.application.AuditLog;
import com.rhl.user.application.driver.DriverService;
import com.rhl.user.domain.driver.DriverProfile;
import com.rhl.user.domain.user.User;
import com.rhl.user.domain.user.UserStatus;
import com.rhl.user.infrastructure.persistence.DriverProfileRepository;
import com.rhl.user.infrastructure.persistence.RefreshTokenRepository;
import com.rhl.user.infrastructure.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
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
    private final DriverProfileRepository driverProfiles;
    private final RefreshTokenRepository refreshTokens;
    private final DriverService drivers;
    private final AuditLog audit;
    private final Clock clock;

    public static final int MAX_PAGE_SIZE = 100;

    /** Every user matching the filters, newest first (FR-ADM). Each filter is optional. */
    @Transactional(readOnly = true)
    public UserAdminViews.UserPage search(UserSearch search, Role role, UserStatus status, UUID before, int limit) {
        int size = Math.clamp(limit, 1, MAX_PAGE_SIZE);
        List<User> page = users.search(search.id(), search.text(), search.phone(),
                status == null ? null : status.name(), role == null ? null : role.name(), before, size);
        UUID next = page.size() == size ? page.getLast().getId() : null;
        return new UserAdminViews.UserPage(page.stream().map(UserAdminViews.UserSummary::of).toList(), next);
    }

    /** One account in full; opening it shows personal data, so each view is audited (BR-014). */
    @Transactional
    public UserAdminViews.UserDetail view(UUID adminId, UUID userId) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        audit.success(adminId, "USER_VIEWED", "USER", userId, Map.of());
        return UserAdminViews.UserDetail.of(user,
                driverProfiles.findById(userId).map(DriverProfile::getReviewStatus).orElse(null));
    }

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

    private static List<String> sorted(Set<Role> roles) {
        return roles.stream().map(Enum::name).sorted().toList();
    }
}
