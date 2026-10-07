package com.rhl.gateway;

import com.rhl.common.web.ApiResponse;
import com.rhl.common.web.ErrorCode;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** Writes 401/403 from the gateway with the same envelope the services use (COM-003). */
@Component
public class JsonErrorWriter {

    private final ObjectMapper objectMapper;

    public JsonErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ServerAuthenticationEntryPoint authenticationEntryPoint() {
        return (exchange, ex) -> write(exchange, ErrorCode.AUTHENTICATION_REQUIRED, "Authentication is required");
    }

    public ServerAccessDeniedHandler accessDeniedHandler() {
        return (exchange, ex) -> write(exchange, ErrorCode.ACCESS_DENIED, "You are not allowed to perform this action");
    }

    public Mono<Void> write(ServerWebExchange exchange, ErrorCode code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        response.setStatusCode(code.status());
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(
                    ApiResponse.error(code, message, List.of(), CorrelationIdWebFilter.of(exchange)));
        } catch (JacksonException e) {
            return Mono.error(e);
        }
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }
}
