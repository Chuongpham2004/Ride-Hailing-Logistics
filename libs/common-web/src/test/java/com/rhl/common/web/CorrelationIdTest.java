package com.rhl.common.web;

import com.rhl.common.id.UuidV7;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdTest {

    @Test
    void keepsSafeIdsAndReplacesUnsafeOnes() {
        assertThat(CorrelationId.sanitizeOrCreate("abc-123")).isEqualTo("abc-123");
        assertThat(CorrelationId.sanitizeOrCreate("bad\r\nheader")).hasSize(36);
        assertThat(CorrelationId.sanitizeOrCreate("x".repeat(65))).hasSize(36);
        assertThat(CorrelationId.sanitizeOrCreate(null)).hasSize(36);
    }

    @Test
    void uuidV7HasVersionVariantAndTimeOrder() throws InterruptedException {
        UUID first = UuidV7.random();
        Thread.sleep(2);
        UUID second = UuidV7.random();

        assertThat(first.version()).isEqualTo(7);
        assertThat(first.variant()).isEqualTo(2);
        assertThat(first.getMostSignificantBits() >>> 16).isLessThan(second.getMostSignificantBits() >>> 16);
    }
}
