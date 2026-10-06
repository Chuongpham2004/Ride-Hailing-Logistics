package com.rhl.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Response envelope shared by every REST endpoint (README §8.2, COM-003). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(String code, String message, T data, String correlationId, List<FieldError> details) {

    public static final String SUCCESS = "SUCCESS";

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(SUCCESS, "Request completed successfully", data, CorrelationId.current(), null);
    }

    public static ApiResponse<Void> error(ErrorCode code, String message, List<FieldError> details) {
        return error(code, message, details, CorrelationId.current());
    }

    public static ApiResponse<Void> error(ErrorCode code, String message, List<FieldError> details,
                                          String correlationId) {
        return new ApiResponse<>(code.name(), message, null, correlationId,
                details == null || details.isEmpty() ? null : List.copyOf(details));
    }

    /** One invalid input; {@code field} names the value, never echoes its content. */
    public record FieldError(String field, String reason) {
    }
}
