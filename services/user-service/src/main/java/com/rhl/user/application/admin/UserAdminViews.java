package com.rhl.user.application.admin;

import com.rhl.common.security.Role;
import com.rhl.user.domain.driver.ReviewStatus;
import com.rhl.user.domain.user.ContactChannel;
import com.rhl.user.domain.user.User;
import com.rhl.user.domain.user.UserStatus;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Read models of the administrator user lookup. */
public final class UserAdminViews {

    private UserAdminViews() {
    }

    public record UserSummary(UUID id, String email, String phone, String fullName, Set<Role> roles,
                              UserStatus status, Instant createdAt) {

        static UserSummary of(User u) {
            return new UserSummary(u.getId(), u.getEmail(), u.getPhone(), u.getFullName(), u.getRoles(),
                    u.getStatus(), u.getCreatedAt());
        }
    }

    /** @param nextBefore pass as {@code before} for the next page; {@code null} on the last one */
    public record UserPage(List<UserSummary> items, UUID nextBefore) {
    }

    /** @param driverReviewStatus the driver profile's review status, or {@code null} without a profile */
    public record UserDetail(UUID id, String email, String phone, String fullName, Set<Role> roles,
                             UserStatus status, boolean emailVerified, boolean phoneVerified, Instant createdAt,
                             Instant updatedAt, ReviewStatus driverReviewStatus) {

        static UserDetail of(User u, ReviewStatus driverReviewStatus) {
            return new UserDetail(u.getId(), u.getEmail(), u.getPhone(), u.getFullName(), u.getRoles(),
                    u.getStatus(), u.isVerified(ContactChannel.EMAIL), u.isVerified(ContactChannel.PHONE),
                    u.getCreatedAt(), u.getUpdatedAt(), driverReviewStatus);
        }
    }
}
