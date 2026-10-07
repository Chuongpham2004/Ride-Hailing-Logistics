package com.rhl.user.application.auth;

import com.rhl.common.web.ApiException;
import com.rhl.common.web.ErrorCode;
import com.rhl.user.UserServiceProperties;
import com.rhl.user.application.AuditLog;
import com.rhl.user.application.notification.NotificationSender;
import com.rhl.user.domain.DomainException;
import com.rhl.user.domain.user.ContactChannel;
import com.rhl.user.domain.user.Identifiers;
import com.rhl.user.domain.user.User;
import com.rhl.user.infrastructure.cache.ClientRateLimiter;
import com.rhl.user.infrastructure.cache.LoginAttemptLimiter;
import com.rhl.user.infrastructure.cache.OneTimeCodeStore;
import com.rhl.user.infrastructure.persistence.RefreshTokenRepository;
import com.rhl.user.infrastructure.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Forgotten password (FR-IAM: password reset). A code goes to the email or phone the person types,
 * if an active account has it; the answer is the same whether or not one does, so the endpoint
 * cannot be used to find accounts. A successful reset ends every session of the account.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final OneTimeCodeStore.Purpose PURPOSE = OneTimeCodeStore.Purpose.PASSWORD_RESET;

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final OneTimeCodeStore codes;
    private final NotificationSender sender;
    private final ClientRateLimiter rateLimiter;
    private final LoginAttemptLimiter loginLimiter;
    private final PasswordEncoder passwordEncoder;
    private final AuditLog audit;
    private final UserServiceProperties properties;
    private final Clock clock;

    /** Always completes the same way for a well-formed request; only the client rate limit is visible. */
    @Transactional
    public void request(String rawIdentifier, String clientIp) {
        if (!rateLimiter.tryAcquire("password-reset", clientIp, properties.rateLimits().passwordResetsPerHour(),
                Duration.ofHours(1))) {
            throw new ApiException(ErrorCode.RATE_LIMIT_EXCEEDED, "Too many requests, try again later");
        }
        String identifier;
        try {
            identifier = Identifiers.loginIdentifier(rawIdentifier);
        } catch (DomainException e) {
            return;
        }
        String subject = OneTimeCodeStore.subjectOf(identifier);
        // Applied before the lookup, so a throttled request looks like any other.
        if (!codes.reserveSend(PURPOSE, subject)) {
            return;
        }
        Optional<User> user = find(identifier).filter(User::isActive);
        if (user.isEmpty()) {
            return;
        }
        String code = codes.issue(PURPOSE, subject);
        sender.sendCode(ContactChannel.of(identifier), identifier, NotificationSender.Purpose.PASSWORD_RESET, code,
                properties.verification().codeTtl());
        audit.success(user.get().getId(), "PASSWORD_RESET_REQUESTED", "USER", user.get().getId(),
                Map.of("channel", ContactChannel.of(identifier).name()));
    }

    /**
     * Sets the new password, marks the address that received the code as verified and revokes
     * every refresh token of the account. Access tokens already issued expire on their own.
     */
    @Transactional
    public void confirm(String rawIdentifier, String code, String newPassword) {
        String identifier;
        try {
            identifier = Identifiers.loginIdentifier(rawIdentifier);
        } catch (DomainException e) {
            throw ContactVerificationService.invalidCode();
        }
        ContactChannel channel = ContactChannel.of(identifier);
        // Before the code is used up, and the same check whether or not the account exists.
        AuthService.validatePassword(newPassword, channel == ContactChannel.EMAIL ? identifier : null,
                channel == ContactChannel.PHONE ? identifier : null);
        if (codes.check(PURPOSE, OneTimeCodeStore.subjectOf(identifier), code) != OneTimeCodeStore.Result.ACCEPTED) {
            throw ContactVerificationService.invalidCode();
        }
        User user = find(identifier).filter(User::isActive).orElseThrow(ContactVerificationService::invalidCode);
        AuthService.validatePassword(newPassword, user.getEmail(), user.getPhone());
        Instant now = clock.instant();
        user.resetPassword(passwordEncoder.encode(newPassword), channel, now);
        int revoked = refreshTokens.revokeAllForUser(user.getId(), now);
        loginLimiter.reset(identifier);
        audit.success(user.getId(), "PASSWORD_RESET", "USER", user.getId(),
                Map.of("channel", channel.name(), "sessionsRevoked", revoked));
    }

    private Optional<User> find(String identifier) {
        return ContactChannel.of(identifier) == ContactChannel.EMAIL ? users.findByEmail(identifier)
                : users.findByPhone(identifier);
    }
}
