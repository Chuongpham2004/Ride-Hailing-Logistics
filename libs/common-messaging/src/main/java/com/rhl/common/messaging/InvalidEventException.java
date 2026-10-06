package com.rhl.common.messaging;

/**
 * An event that does not match its contract. Never retried: producers fail the business
 * transaction, consumers send the record straight to {@code <topic>.DLT}.
 */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }
}
