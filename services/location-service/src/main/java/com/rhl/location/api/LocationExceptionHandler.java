package com.rhl.location.api;

import com.rhl.common.web.ApiResponse;
import com.rhl.common.web.ErrorCode;
import com.rhl.location.domain.TelemetryRejectedException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/** Maps telemetry rejections and infrastructure outages; runs before the shared catch-all handler. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocationExceptionHandler {

    @ExceptionHandler(TelemetryRejectedException.class)
    public ResponseEntity<ApiResponse<Void>> handleRejected(TelemetryRejectedException ex) {
        ErrorCode code = ErrorCode.BUSINESS_RULE_VIOLATION;
        return ResponseEntity.status(code.status())
                .body(ApiResponse.error(code, ex.reason() + ": " + ex.getMessage(), List.of()));
    }

    /** {@code @PreAuthorize} failures surface here, not in the filter chain. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleDenied(AccessDeniedException ex) {
        ErrorCode code = ErrorCode.ACCESS_DENIED;
        return ResponseEntity.status(code.status())
                .body(ApiResponse.error(code, "You are not allowed to perform this action", List.of()));
    }

    /** Redis or the database unreachable: a retryable 503, not a 500. */
    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnavailable(DataAccessResourceFailureException ex) {
        ErrorCode code = ErrorCode.DEPENDENCY_UNAVAILABLE;
        return ResponseEntity.status(code.status())
                .body(ApiResponse.error(code, "Service temporarily unavailable, try again shortly", List.of()));
    }
}
