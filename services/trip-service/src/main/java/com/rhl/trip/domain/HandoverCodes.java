package com.rhl.trip.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Short numeric codes the customer hands over: to the driver at pickup (OTP to start the trip)
 * and, for deliveries, through the recipient at drop-off (proof of delivery).
 */
public final class HandoverCodes {

    private static final SecureRandom RANDOM = new SecureRandom();

    private HandoverCodes() {
    }

    public static String generate(int length) {
        if (length < 4 || length > 8) {
            throw new IllegalArgumentException("Code length must be 4 to 8 digits");
        }
        StringBuilder code = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            code.append(RANDOM.nextInt(10));
        }
        return code.toString();
    }

    /** Constant-time, so response timing does not reveal how many digits were right. */
    static boolean matches(String expected, String entered) {
        return entered != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                entered.getBytes(StandardCharsets.US_ASCII));
    }
}
