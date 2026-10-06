package com.rhl.common.id;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Time-ordered UUID version 7 (RFC 9562) for IDs shared between services (DR-006): sortable by
 * creation time, index friendly and free of PII.
 *
 * <p>IDs from one JVM are strictly increasing, also within the same millisecond (RFC 9562 §6.2,
 * method 1): {@code rand_a} is a 12-bit counter that starts at a random value below 2048 each
 * millisecond and is incremented for every further ID; when it would overflow, the timestamp
 * moves one millisecond ahead. Keyset pagination and "newest first" lists rely on this order
 * (PostgreSQL compares {@code uuid} byte by byte, i.e. timestamp, then counter).
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int COUNTER_MAX = 0xFFF;
    /** New milliseconds start low enough to leave room for at least 2048 more IDs. */
    private static final int COUNTER_SEED_BOUND = 1 << 11;

    private static long lastMillis = -1;
    private static int counter;

    private UuidV7() {
    }

    public static UUID random() {
        long millis;
        int seq;
        synchronized (UuidV7.class) {
            long now = System.currentTimeMillis();
            if (now > lastMillis) {
                lastMillis = now;
                counter = RANDOM.nextInt(COUNTER_SEED_BOUND);
            } else if (counter < COUNTER_MAX) {
                // Same millisecond, or the clock went back: keep the last timestamp, count up.
                counter++;
            } else {
                lastMillis++;
                counter = RANDOM.nextInt(COUNTER_SEED_BOUND);
            }
            millis = lastMillis;
            seq = counter;
        }
        long msb = (millis << 16) | (0x7L << 12) | seq;
        long lsb = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(msb, lsb);
    }

    public static String randomString() {
        return random().toString();
    }
}
