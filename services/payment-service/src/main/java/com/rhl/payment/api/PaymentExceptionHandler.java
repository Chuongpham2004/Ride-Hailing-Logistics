package com.rhl.payment.api;

import com.rhl.common.web.ApiResponse;
import com.rhl.common.web.ErrorCode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/** Maps conflicts and infrastructure outages; runs before the shared catch-all handler. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PaymentExceptionHandler {

    /** A constraint refused a write the checks let through, e.g. two writers racing. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(DataIntegrityViolationException ex) {
        return respond(ErrorCode.CONFLICT, "The request conflicts with the current state, reload and try again");
    }

    /** {@code @PreAuthorize} failures surface here, not in the filter chain. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleDenied(AccessDeniedException ex) {
        return respond(ErrorCode.ACCESS_DENIED, "You are not allowed to perform this action");
    }

    /** The database unreachable: a retryable 503, not a 500. */
    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnavailable(DataAccessResourceFailureException ex) {
        return respond(ErrorCode.DEPENDENCY_UNAVAILABLE, "Service temporarily unavailable, try again shortly");
    }

    private static ResponseEntity<ApiResponse<Void>> respond(ErrorCode code, String message) {
        return ResponseEntity.status(code.status()).body(ApiResponse.error(code, message, List.of()));
    }
}
