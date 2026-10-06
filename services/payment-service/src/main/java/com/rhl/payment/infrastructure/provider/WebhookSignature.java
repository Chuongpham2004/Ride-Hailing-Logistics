package com.rhl.payment.infrastructure.provider;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Provider webhook signatures (FR-PAY: callbacks are verified and replays refused). Header
 * {@value #HEADER}: {@code t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>}. The
 * timestamp is part of the signed content, so an old callback cannot be re-sent with a fresh one.
 */
public final class WebhookSignature {

    public static final String HEADER = "X-Rhl-Signature";

    private WebhookSignature() {
    }

    public static class InvalidSignatureException extends RuntimeException {

        InvalidSignatureException(String message) {
            super(message);
        }
    }

    public static String header(String secret, Instant signedAt, String body) {
        long t = signedAt.getEpochSecond();
        return "t=" + t + ",v1=" + hmac(secret, t + "." + body);
    }

    /**
     * @return when the provider signed the callback
     * @throws InvalidSignatureException when the header is malformed, the signature does not match
     *                                   or the timestamp is outside {@code tolerance}
     */
    public static Instant verify(String header, String body, String secret, Instant now, Duration tolerance) {
        if (header == null || body == null) {
            throw new InvalidSignatureException("Missing signature");
        }
        Long t = null;
        String v1 = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if ("t".equals(kv[0])) {
                try {
                    t = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    throw new InvalidSignatureException("Malformed timestamp");
                }
            } else if ("v1".equals(kv[0])) {
                v1 = kv[1];
            }
        }
        if (t == null || v1 == null) {
            throw new InvalidSignatureException("Malformed signature header");
        }
        byte[] expected = hmac(secret, t + "." + body).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, v1.toLowerCase().getBytes(StandardCharsets.US_ASCII))) {
            throw new InvalidSignatureException("Signature mismatch");
        }
        Instant signedAt = Instant.ofEpochSecond(t);
        if (Duration.between(signedAt, now).abs().compareTo(tolerance) > 0) {
            throw new InvalidSignatureException("Signature timestamp outside the accepted window");
        }
        return signedAt;
    }

    private static String hmac(String secret, String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
