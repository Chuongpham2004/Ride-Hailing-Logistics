package com.rhl.user.application.auth;

import com.rhl.common.security.Role;
import com.rhl.common.web.ApiException;
import com.rhl.common.web.ErrorCode;
import com.rhl.user.UserServiceProperties;
import com.rhl.user.application.AuditLog;
import com.rhl.user.domain.DomainException;
import com.rhl.user.domain.auth.RefreshToken;
import com.rhl.user.domain.user.Identifiers;
import com.rhl.user.domain.user.User;
import com.rhl.user.infrastructure.cache.ClientRateLimiter;
import com.rhl.user.infrastructure.cache.LoginAttemptLimiter;
import com.rhl.user.infrastructure.cache.TokenRevocationStore;
import com.rhl.user.infrastructure.persistence.RefreshTokenRepository;
import com.rhl.user.infrastructure.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Sign-up, login, refresh-token rotation and logout (FR-IAM-001…008). */
@Service
@RequiredArgsConstructor
public class AuthService {

    public static final int MIN_PASSWORD_LENGTH = 8;

    private static final String INVALID_CREDENTIALS = "Invalid login or password";

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final LoginAttemptLimiter limiter;
    private final TokenRevocationStore revocations;
    private final AuditLog audit;
    private final ClientRateLimiter rateLimiter;
    private final UserServiceProperties properties;
    private final Clock clock;

    /**
     * Compared against when the account does not exist, so both paths cost one hash check.
     * Computed on first use; a race only computes it twice.
     */
    private volatile String dummyHash;

    public record RegisterCommand(String email, String phone, String password, String fullName, Role role) {
    }

    public record TokenPair(String accessToken, Instant accessTokenExpiresAt, String refreshToken,
                            Instant refreshTokenExpiresAt) {
    }

    /** @param clientIp limits bulk sign-ups and probing for existing accounts (NFR-SEC-007) */
    @Transactional
    public User register(RegisterCommand cmd, String clientIp) {
        if (!rateLimiter.tryAcquire("register", clientIp, properties.rateLimits().registrationsPerHour(),
                Duration.ofHours(1))) {
            throw new ApiException(ErrorCode.RATE_LIMIT_EXCEEDED, "Too many sign-ups, try again later");
        }
        String email = blankToNull(cmd.email()) == null ? null : Identifiers.email(cmd.email());
        String phone = blankToNull(cmd.phone()) == null ? null : Identifiers.phone(cmd.phone());
        validatePassword(cmd.password(), email, phone);
        if ((email != null && users.existsByEmail(email)) || (phone != null && users.existsByPhone(phone))) {
            throw ApiException.conflict("An account with this email or phone number already exists");
        }
        Instant now = clock.instant();
        User user = User.register(email, phone, passwordEncoder.encode(cmd.password()), cmd.fullName(), cmd.role(),
                now);
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent sign-up for the same identifier.
            throw ApiException.conflict("An account with this email or phone number already exists");
        }
        audit.success(user.getId(), "USER_REGISTERED", "USER", user.getId(), Map.of("role", cmd.role().name()));
        return user;
    }

    /** Same answer for unknown account, wrong password and inactive account (FR-IAM-006). */
    @Transactional
    public TokenPair login(String rawIdentifier, String password) {
        String identifier;
        try {
            identifier = Identifiers.loginIdentifier(rawIdentifier);
        } catch (DomainException e) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, INVALID_CREDENTIALS);
        }
        if (limiter.isBlocked(identifier)) {
            throw new ApiException(ErrorCode.RATE_LIMIT_EXCEEDED, "Too many failed attempts, try again later");
        }
        Optional<User> found = identifier.contains("@") ? users.findByEmail(identifier) : users.findByPhone(identifier);
        String hash = found.map(User::getPasswordHash).orElseGet(this::dummyHash);
        boolean passwordOk = passwordEncoder.matches(password, hash);
        if (found.isEmpty() || !passwordOk || !found.get().isActive()) {
            limiter.recordFailure(identifier);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, INVALID_CREDENTIALS);
        }
        limiter.reset(identifier);
        return issue(found.get(), null, clock.instant());
    }

    /**
     * Rotates the refresh token. Presenting a token that was already rotated or revoked revokes
     * its whole family: either the client is replaying, or the token leaked.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public TokenPair refresh(String rawRefreshToken) {
        Instant now = clock.instant();
        RefreshToken current = refreshTokens.findByTokenHash(TokenService.hash(rawRefreshToken))
                .orElseThrow(AuthService::invalidRefreshToken);
        if (current.isRevoked()) {
            refreshTokens.revokeFamily(current.getFamilyId(), now);
            throw invalidRefreshToken();
        }
        if (!current.isUsable(now)) {
            throw invalidRefreshToken();
        }
        User user = users.findById(current.getUserId()).filter(User::isActive)
                .orElseThrow(AuthService::invalidRefreshToken);
        current.revoke(now);
        return issue(user, current.getFamilyId(), now);
    }

    /** Revokes the refresh-token family and the presented access token for its remaining lifetime. */
    @Transactional
    public void logout(UUID userId, String accessTokenId, Instant accessTokenExpiresAt, String rawRefreshToken) {
        Instant now = clock.instant();
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            refreshTokens.findByTokenHash(TokenService.hash(rawRefreshToken))
                    .filter(t -> t.getUserId().equals(userId))
                    .ifPresent(t -> refreshTokens.revokeFamily(t.getFamilyId(), now));
        }
        if (accessTokenId != null && accessTokenExpiresAt != null) {
            revocations.revoke(accessTokenId, Duration.between(now, accessTokenExpiresAt));
        }
    }

    private TokenPair issue(User user, UUID familyId, Instant now) {
        TokenService.AccessToken access = tokens.issueAccessToken(user, now);
        String refresh = tokens.newRefreshToken();
        Instant refreshExpiry = tokens.refreshTokenExpiry(now);
        refreshTokens.save(RefreshToken.issue(user.getId(), familyId, TokenService.hash(refresh), refreshExpiry, now));
        return new TokenPair(access.value(), access.expiresAt(), refresh, refreshExpiry);
    }

    private String dummyHash() {
        String hash = dummyHash;
        if (hash == null) {
            hash = passwordEncoder.encode("not-a-real-password");
            dummyHash = hash;
        }
        return hash;
    }

    static void validatePassword(String password, String email, String phone) {
        // bcrypt only uses the first 72 bytes; longer input would be silently truncated.
        if (password == null || password.length() < MIN_PASSWORD_LENGTH
                || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw DomainException.rule("Password must be " + MIN_PASSWORD_LENGTH + " to 72 bytes long");
        }
        if (password.isBlank() || password.equalsIgnoreCase(email) || password.equals(phone)) {
            throw DomainException.rule("Password is too weak");
        }
    }

    private static ApiException invalidRefreshToken() {
        return new ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "Refresh token is invalid or expired");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
