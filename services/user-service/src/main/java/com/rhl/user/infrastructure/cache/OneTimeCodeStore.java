package com.rhl.user.infrastructure.cache;

import com.rhl.user.UserServiceProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * Short-lived one-time codes in Redis ({@code auth:otp:{purpose}:{subject}}). Only a hash of the
 * code is stored, salted with its key; a new code replaces the previous one; each wrong entry
 * counts and the code is discarded after {@code maxAttempts}, so 6 digits cannot be guessed
 * online. Subjects are opaque (a user ID, or the hash of an email/phone), never PII.
 */
@Component
public class OneTimeCodeStore {

    public enum Purpose {
        VERIFY_EMAIL,
        VERIFY_PHONE,
        PASSWORD_RESET
    }

    public enum Result {
        ACCEPTED,
        WRONG_CODE,
        /** Expired, never issued, already used, or discarded after too many wrong entries. */
        NO_CODE
    }

    private static final String PREFIX = "auth:otp:";
    private static final String COOLDOWN_PREFIX = "auth:otp-cooldown:";
    private static final String SENT_PREFIX = "auth:otp-sent:";
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * KEYS[1] code hash; ARGV[1] candidate hash, ARGV[2] max attempts.
     * Returns 1 accepted (code consumed), 0 wrong, -1 no usable code.
     */
    private static final RedisScript<Long> CHECK = RedisScript.of("""
            local stored = redis.call('HGET', KEYS[1], 'hash')
            if not stored then return -1 end
            if stored == ARGV[1] then
              redis.call('DEL', KEYS[1])
              return 1
            end
            local attempts = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
            if attempts >= tonumber(ARGV[2]) then redis.call('DEL', KEYS[1]) end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final UserServiceProperties.Verification config;

    public OneTimeCodeStore(StringRedisTemplate redis, UserServiceProperties properties) {
        this.redis = redis;
        this.config = properties.verification();
    }

    /**
     * Reserves the right to send a code: at most one per cooldown and {@code maxSendsPerHour}
     * per hour for this purpose and subject.
     *
     * @return {@code false} when a code may not be sent now
     */
    public boolean reserveSend(Purpose purpose, String subject) {
        String cooldown = COOLDOWN_PREFIX + purpose + ":" + subject;
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(cooldown, "1", config.resendCooldown()))) {
            return false;
        }
        String sent = SENT_PREFIX + purpose + ":" + subject;
        Long count = redis.opsForValue().increment(sent);
        if (count != null && count == 1) {
            redis.expire(sent, Duration.ofHours(1));
        }
        return count != null && count <= config.maxSendsPerHour();
    }

    /** A fresh 6-digit code; any earlier code for the same purpose and subject stops working. */
    public String issue(Purpose purpose, String subject) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        String key = key(purpose, subject);
        redis.delete(key);
        redis.opsForHash().put(key, "hash", hash(key, code));
        redis.opsForHash().put(key, "attempts", "0");
        redis.expire(key, config.codeTtl());
        return code;
    }

    public Result check(Purpose purpose, String subject, String code) {
        String key = key(purpose, subject);
        Long result = redis.execute(CHECK, List.of(key), hash(key, code), Integer.toString(config.maxAttempts()));
        if (result == null || result < 0) {
            return Result.NO_CODE;
        }
        return result == 1 ? Result.ACCEPTED : Result.WRONG_CODE;
    }

    /** Opaque Redis subject for an email or phone number. */
    public static String subjectOf(String normalizedIdentifier) {
        return sha256(normalizedIdentifier);
    }

    private static String key(Purpose purpose, String subject) {
        return PREFIX + purpose + ":" + subject;
    }

    private static String hash(String key, String code) {
        return sha256(key + ":" + code);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
