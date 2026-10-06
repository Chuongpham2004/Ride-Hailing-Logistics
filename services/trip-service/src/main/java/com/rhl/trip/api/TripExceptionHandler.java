package com.rhl.trip.api;

import com.rhl.common.web.ApiResponse;
import com.rhl.common.web.ErrorCode;
import com.rhl.trip.domain.DomainException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/** Maps domain rule violations and infrastructure outages; runs before the shared catch-all handler. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TripExceptionHandler {

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiResponse<Void>> handleDomain(DomainException ex) {
        ErrorCode code = switch (ex.kind()) {
            case INVALID_TRIP_STATE -> ErrorCode.INVALID_TRIP_STATE;
            case OFFER_EXPIRED -> ErrorCode.OFFER_EXPIRED;
            case DRIVER_ALREADY_ASSIGNED -> ErrorCode.DRIVER_ALREADY_ASSIGNED;
            case RULE_VIOLATION -> ErrorCode.BUSINESS_RULE_VIOLATION;
        };
        return respond(code, ex.getMessage());
    }

    /**
     * A unique index refused a write the pre-checks let through, e.g. two trips created at once
     * by one customer under different keys (FR-TRIP-007).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(DataIntegrityViolationException ex) {
        return respond(ErrorCode.CONFLICT, "The request conflicts with the current state, reload and try again");
    }

    /** {@code @PreAuthorize} failures surface here, not in the filter chain. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleDenied(AccessDeniedException ex) {
        return respond(ErrorCode.ACCESS_DENIED, "You are not allowed to perform this action");
    }

    /** Redis or the database unreachable: a retryable 503, not a 500. */
    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnavailable(DataAccessResourceFailureException ex) {
        return respond(ErrorCode.DEPENDENCY_UNAVAILABLE, "Service temporarily unavailable, try again shortly");
    }

    private static ResponseEntity<ApiResponse<Void>> respond(ErrorCode code, String message) {
        return ResponseEntity.status(code.status()).body(ApiResponse.error(code, message, List.of()));
    }
}
