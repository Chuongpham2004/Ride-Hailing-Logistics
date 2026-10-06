package com.rhl.pricing.domain;

/** A business rule rejected the operation. The API layer maps each kind to an error code (README §8.3). */
public class DomainException extends RuntimeException {

    public enum Kind {
        /** The quote ran out (422 QUOTE_EXPIRED). */
        QUOTE_EXPIRED,
        /** Allowed in principle but a precondition is not met (422). */
        RULE_VIOLATION
    }

    private final Kind kind;

    private DomainException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public static DomainException quoteExpired(String message) {
        return new DomainException(Kind.QUOTE_EXPIRED, message);
    }

    public static DomainException rule(String message) {
        return new DomainException(Kind.RULE_VIOLATION, message);
    }

    public Kind kind() {
        return kind;
    }
}
