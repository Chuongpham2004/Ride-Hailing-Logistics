package com.rhl.user.domain.user;

import com.rhl.common.id.UuidV7;
import com.rhl.common.security.Role;
import com.rhl.user.domain.DomainException;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** An account: customer, driver or staff (FR-IAM-001). The password hash never leaves this service. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;

    private String email;

    private String phone;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role")
    @Enumerated(EnumType.STRING)
    private Set<Role> roles = EnumSet.noneOf(Role.class);

    @Version
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "phone_verified_at")
    private Instant phoneVerifiedAt;

    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    /**
     * @param email normalized email or {@code null}
     * @param phone normalized phone or {@code null}; at least one identifier is required
     */
    public static User register(String email, String phone, String passwordHash, String fullName, Role role,
                                Instant now) {
        if (email == null && phone == null) {
            throw DomainException.rule("Email or phone number is required");
        }
        if (!role.isSelfService()) {
            throw DomainException.rule("This role cannot be chosen at sign-up");
        }
        User user = new User();
        user.id = UuidV7.random();
        user.email = email;
        user.phone = phone;
        user.passwordHash = passwordHash;
        user.fullName = fullName.strip();
        user.status = UserStatus.ACTIVE;
        user.roles.add(role);
        user.createdAt = now;
        user.updatedAt = now;
        return user;
    }

    /** Staff accounts are created by an administrator or the bootstrap, never by sign-up. */
    public static User provisionStaff(String email, String passwordHash, String fullName, Set<Role> roles,
                                      Instant now) {
        if (roles.isEmpty()) {
            throw DomainException.rule("A user needs at least one role");
        }
        User user = new User();
        user.id = UuidV7.random();
        user.email = email;
        user.passwordHash = passwordHash;
        user.fullName = fullName.strip();
        user.status = UserStatus.ACTIVE;
        user.roles.addAll(roles);
        user.createdAt = now;
        user.updatedAt = now;
        return user;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    /** The account's address on {@code channel}, {@code null} when it has none. */
    public String contact(ContactChannel channel) {
        return channel == ContactChannel.EMAIL ? email : phone;
    }

    public boolean isVerified(ContactChannel channel) {
        return (channel == ContactChannel.EMAIL ? emailVerifiedAt : phoneVerifiedAt) != null;
    }

    /** At least one contact was proven to reach this person (required before a driver applies). */
    public boolean hasVerifiedContact() {
        return emailVerifiedAt != null || phoneVerifiedAt != null;
    }

    public void markVerified(ContactChannel channel, Instant now) {
        if (contact(channel) == null) {
            throw DomainException.rule("The account has no " + channel.name().toLowerCase() + " to verify");
        }
        if (channel == ContactChannel.EMAIL) {
            emailVerifiedAt = now;
        } else {
            phoneVerifiedAt = now;
        }
        updatedAt = now;
    }

    /**
     * A reset proves control of the email or phone the code was sent to, so that contact counts
     * as verified too.
     */
    public void resetPassword(String newPasswordHash, ContactChannel provenBy, Instant now) {
        passwordHash = newPasswordHash;
        passwordChangedAt = now;
        markVerified(provenBy, now);
    }

    public boolean has(Role role) {
        return roles.contains(role);
    }

    public void replaceRoles(Set<Role> newRoles, Instant now) {
        if (newRoles.isEmpty()) {
            throw DomainException.rule("A user needs at least one role");
        }
        roles.clear();
        roles.addAll(newRoles);
        updatedAt = now;
    }

    public void changeStatus(UserStatus newStatus, Instant now) {
        status = newStatus;
        updatedAt = now;
    }

    public Set<Role> getRoles() {
        return Collections.unmodifiableSet(roles);
    }
}
