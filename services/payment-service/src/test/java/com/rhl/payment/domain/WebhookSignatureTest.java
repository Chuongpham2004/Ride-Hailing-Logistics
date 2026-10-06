package com.rhl.payment.domain;

import com.rhl.payment.infrastructure.provider.WebhookSignature;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookSignatureTest {

    private static final String SECRET = "test-webhook-secret-0123456789";
    private static final Instant NOW = Instant.parse("2026-10-07T08:00:00Z");
    private static final Duration TOLERANCE = Duration.ofMinutes(5);
    private static final String BODY = "{\"eventId\":\"evt_1\",\"type\":\"charge.succeeded\",\"amount\":27000}";

    @Test
    void aFreshSignatureOverTheExactBodyIsAccepted() {
        String header = WebhookSignature.header(SECRET, NOW.minusSeconds(30), BODY);

        assertThat(WebhookSignature.verify(header, BODY, SECRET, NOW, TOLERANCE)).isEqualTo(NOW.minusSeconds(30));
        assertThat(WebhookSignature.verify(header.toUpperCase().replace("T=", "t=").replace("V1=", "v1="), BODY,
                SECRET, NOW, TOLERANCE)).isNotNull();
    }

    @Test
    void anyChangeToBodySecretOrTimestampBreaksIt() {
        String header = WebhookSignature.header(SECRET, NOW, BODY);

        assertRejected(header, BODY.replace("27000", "27001"));
        assertThatThrownBy(() -> WebhookSignature.verify(header, BODY, SECRET + "x", NOW, TOLERANCE))
                .isInstanceOf(WebhookSignature.InvalidSignatureException.class);
        // A replayed signature with a new timestamp does not match: the timestamp is signed too.
        String forged = header.replace("t=" + NOW.getEpochSecond(), "t=" + (NOW.getEpochSecond() + 60));
        assertRejected(forged, BODY);
    }

    @Test
    void oldOrFutureCallbacksAreRefusedEvenWithAValidSignature() {
        assertRejected(WebhookSignature.header(SECRET, NOW.minus(Duration.ofMinutes(6)), BODY), BODY);
        assertRejected(WebhookSignature.header(SECRET, NOW.plus(Duration.ofMinutes(6)), BODY), BODY);
    }

    @Test
    void malformedHeadersAreRefused() {
        assertRejected(null, BODY);
        assertRejected("", BODY);
        assertRejected("t=abc,v1=00", BODY);
        assertRejected("v1=deadbeef", BODY);
    }

    private static void assertRejected(String header, String body) {
        assertThatThrownBy(() -> WebhookSignature.verify(header, body, SECRET, NOW, TOLERANCE))
                .isInstanceOf(WebhookSignature.InvalidSignatureException.class);
    }
}
