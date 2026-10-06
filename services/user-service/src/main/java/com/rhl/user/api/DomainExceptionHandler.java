package com.rhl.user.api;

import com.rhl.common.web.ApiResponse;
import com.rhl.common.web.ErrorCode;
import com.rhl.user.domain.DomainException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/** Maps domain rule violations and infrastructure outages; runs before the shared catch-all handler. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DomainExceptionHandler {

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiResponse<Void>> handleDomain(DomainException ex) {
        ErrorCode code = ex.kind() == DomainException.Kind.INVALID_STATE
                ? ErrorCode.INVALID_STATE
                : ErrorCode.BUSINESS_RULE_VIOLATION;
        return ResponseEntity.status(code.status()).body(ApiResponse.error(code, ex.getMessage(), List.of()));
    }

    /** {@code @PreAuthorize} failures surface here, not in the filter chain. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleDenied(AccessDeniedException ex) {
        ErrorCode code = ErrorCode.ACCESS_DENIED;
        return ResponseEntity.status(code.status())
                .body(ApiResponse.error(code, "You are not allowed to perform this action", List.of()));
    }

    /** Redis or database unreachable (e.g. login limiter): a retryable 503, not a 500. */
    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnavailable(DataAccessResourceFailureException ex) {
        ErrorCode code = ErrorCode.DEPENDENCY_UNAVAILABLE;
        return ResponseEntity.status(code.status())
                .body(ApiResponse.error(code, "Service temporarily unavailable, try again shortly", List.of()));
    }
}
