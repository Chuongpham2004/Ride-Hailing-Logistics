package com.rhl.gateway;

import com.rhl.common.web.ErrorCode;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

/**
 * Turns errors raised while routing into the standard envelope (COM-003). A downstream service
 * that is down or too slow becomes {@code 503 DEPENDENCY_UNAVAILABLE} instead of a bare 500, so
 * clients know the request can be retried. Runs before Spring Boot's default handler (order -1).
 */
@Component
@Order(-2)
public class GatewayErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    private final JsonErrorWriter writer;

    public GatewayErrorHandler(JsonErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        if (isDependencyFailure(ex)) {
            log.warn("Route {} unavailable for {} {}: {}", routeId(exchange), exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath(), rootMessage(ex));
            return writer.write(exchange, ErrorCode.DEPENDENCY_UNAVAILABLE,
                    "Service temporarily unavailable, try again shortly");
        }
        if (ex instanceof ResponseStatusException rse) {
            return writeStatus(exchange, rse.getStatusCode(), ex);
        }
        log.error("Unhandled gateway error for {} {}", exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(), ex);
        return writer.write(exchange, ErrorCode.INTERNAL_ERROR, "An unexpected error occurred");
    }

    private Mono<Void> writeStatus(ServerWebExchange exchange, HttpStatusCode status, Throwable ex) {
        if (status.value() == HttpStatus.NOT_FOUND.value() || status.value() == HttpStatus.METHOD_NOT_ALLOWED.value()) {
            return writer.write(exchange, ErrorCode.RESOURCE_NOT_FOUND, "Resource not found");
        }
        if (status.is4xxClientError()) {
            return writer.write(exchange, ErrorCode.VALIDATION_ERROR, "Malformed request");
        }
        log.error("Gateway error {} for {} {}", status.value(), exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(), ex);
        return writer.write(exchange, ErrorCode.INTERNAL_ERROR, "An unexpected error occurred");
    }

    /** Connection refused, unknown host, connect/read timeout, or the gateway's own 503/504. */
    static boolean isDependencyFailure(Throwable ex) {
        if (ex instanceof ResponseStatusException rse) {
            int status = rse.getStatusCode().value();
            if (status == HttpStatus.SERVICE_UNAVAILABLE.value() || status == HttpStatus.GATEWAY_TIMEOUT.value()) {
                return true;
            }
        }
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ConnectException || t instanceof UnknownHostException
                    || t instanceof ConnectTimeoutException || t instanceof ReadTimeoutException
                    || t instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static String routeId(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return route == null ? "?" : route.getId();
    }

    private static String rootMessage(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
