package com.rhl.common.id;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Time-ordered UUID version 7 (RFC 9562) for IDs shared between services (DR-006): sortable by
 * creation time, index friendly and free of PII.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID random() {
        long millis = System.currentTimeMillis();
        long randA = RANDOM.nextInt(1 << 12);
        long msb = (millis << 16) | (0x7L << 12) | randA;
        long lsb = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(msb, lsb);
    }

    public static String randomString() {
        return random().toString();
    }
}
