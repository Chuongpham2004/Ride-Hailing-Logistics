package com.rhl.user.domain;

/** A business rule rejected the operation. Mapped to HTTP 409/422 by the API layer. */
public class DomainException extends RuntimeException {

    public enum Kind {
        /** The aggregate is not in a state that allows the operation (409). */
        INVALID_STATE,
        /** The operation is allowed in this state but its preconditions are not met (422). */
        RULE_VIOLATION
    }

    private final Kind kind;

    private DomainException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public static DomainException invalidState(String message) {
        return new DomainException(Kind.INVALID_STATE, message);
    }

    public static DomainException rule(String message) {
        return new DomainException(Kind.RULE_VIOLATION, message);
    }

    public Kind kind() {
        return kind;
    }
}
