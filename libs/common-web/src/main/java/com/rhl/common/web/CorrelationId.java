package com.rhl.common.web;

import com.rhl.common.id.UuidV7;
import org.slf4j.MDC;

import java.util.regex.Pattern;

/** Correlation ID propagation (COM-004): HTTP header, MDC key and Kafka header share one name. */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private CorrelationId() {
    }

    /** The caller's ID when it is safe to log and echo back, otherwise a fresh one. */
    public static String sanitizeOrCreate(String candidate) {
        return candidate != null && SAFE.matcher(candidate).matches() ? candidate : UuidV7.randomString();
    }

    /** The ID bound to the current thread, or {@code null} outside a request or consumer. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
