package com.rhl.user.application.auth;

import com.rhl.common.web.ApiException;
import com.rhl.common.web.ErrorCode;
import com.rhl.user.UserServiceProperties;
import com.rhl.user.application.AuditLog;
import com.rhl.user.application.notification.NotificationSender;
import com.rhl.user.domain.DomainException;
import com.rhl.user.domain.user.ContactChannel;
import com.rhl.user.domain.user.User;
import com.rhl.user.infrastructure.cache.OneTimeCodeStore;
import com.rhl.user.infrastructure.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Proves that the account's email or phone reaches its owner (FR-IAM: verify email/phone): a
 * one-time code is sent to the address on the account and entered back by the signed-in user.
 * The code is bound to that address, so it stops working if the address changes.
 */
@Service
@RequiredArgsConstructor
public class ContactVerificationService {

    private final UserRepository users;
    private final OneTimeCodeStore codes;
    private final NotificationSender sender;
    private final AuditLog audit;
    private final UserServiceProperties properties;
    private final Clock clock;

    /** @param destination masked, so the person can tell where to look */
    public record CodeSent(ContactChannel channel, String destination, Duration expiresIn, Duration resendAfter) {
    }

    public CodeSent send(UUID userId, ContactChannel channel) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        String address = user.contact(channel);
        if (address == null) {
            throw DomainException.rule("The account has no " + name(channel));
        }
        if (user.isVerified(channel)) {
            throw DomainException.invalidState("The " + name(channel) + " is already verified");
        }
        OneTimeCodeStore.Purpose purpose = purpose(channel);
        String subject = subject(user, channel);
        UserServiceProperties.Verification config = properties.verification();
        if (!codes.reserveSend(purpose, subject)) {
            throw new ApiException(ErrorCode.RATE_LIMIT_EXCEEDED, "A code was sent recently, try again later");
        }
        String code = codes.issue(purpose, subject);
        sender.sendCode(channel, address, NotificationSender.Purpose.VERIFY_CONTACT, code, config.codeTtl());
        return new CodeSent(channel, ContactChannel.mask(address), config.codeTtl(), config.resendCooldown());
    }

    /** Idempotent: confirming an address that is already verified changes nothing. */
    @Transactional
    public User confirm(UUID userId, ContactChannel channel, String code) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        if (user.contact(channel) == null) {
            throw DomainException.rule("The account has no " + name(channel));
        }
        if (user.isVerified(channel)) {
            return user;
        }
        if (codes.check(purpose(channel), subject(user, channel), code) != OneTimeCodeStore.Result.ACCEPTED) {
            throw invalidCode();
        }
        user.markVerified(channel, clock.instant());
        audit.success(userId, "CONTACT_VERIFIED", "USER", userId, Map.of("channel", channel.name()));
        return user;
    }

    static ApiException invalidCode() {
        return new ApiException(ErrorCode.VALIDATION_ERROR, "The code is invalid or has expired");
    }

    private static OneTimeCodeStore.Purpose purpose(ContactChannel channel) {
        return channel == ContactChannel.EMAIL ? OneTimeCodeStore.Purpose.VERIFY_EMAIL
                : OneTimeCodeStore.Purpose.VERIFY_PHONE;
    }

    private static String subject(User user, ContactChannel channel) {
        return user.getId() + ":" + OneTimeCodeStore.subjectOf(user.contact(channel));
    }

    private static String name(ContactChannel channel) {
        return channel == ContactChannel.EMAIL ? "email" : "phone number";
    }
}
