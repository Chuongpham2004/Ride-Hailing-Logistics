package com.rhl.common.web;

import java.util.List;

/**
 * Thrown by application code to answer with a specific {@link ErrorCode}. The message is shown
 * to clients, so it must not contain internal details or PII.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final List<ApiResponse.FieldError> details;

    public ApiException(ErrorCode code, String message) {
        this(code, message, List.of());
    }

    public ApiException(ErrorCode code, String message, List<ApiResponse.FieldError> details) {
        super(message);
        this.code = code;
        this.details = List.copyOf(details);
    }

    public ErrorCode code() {
        return code;
    }

    public List<ApiResponse.FieldError> details() {
        return details;
    }

    public static ApiException notFound(String what) {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, what + " not found");
    }

    public static ApiException forbidden() {
        return new ApiException(ErrorCode.ACCESS_DENIED, "You are not allowed to perform this action");
    }

    public static ApiException conflict(String message) {
        return new ApiException(ErrorCode.CONFLICT, message);
    }

    public static ApiException rule(String message) {
        return new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION, message);
    }

    public static ApiException invalidState(String message) {
        return new ApiException(ErrorCode.INVALID_STATE, message);
    }
}
