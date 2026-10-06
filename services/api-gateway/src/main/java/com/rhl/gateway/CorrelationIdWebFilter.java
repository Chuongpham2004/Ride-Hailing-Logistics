package com.rhl.gateway;

import com.rhl.common.web.CorrelationId;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Assigns or sanitizes {@code X-Correlation-Id} (COM-004) before security runs, forwards it to
 * the downstream service and echoes it to the client.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdWebFilter implements WebFilter {

    public static final String ATTRIBUTE = CorrelationIdWebFilter.class.getName() + ".id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = CorrelationId.sanitizeOrCreate(
                exchange.getRequest().getHeaders().getFirst(CorrelationId.HEADER));
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> h.set(CorrelationId.HEADER, correlationId))
                .build();
        exchange.getResponse().getHeaders().set(CorrelationId.HEADER, correlationId);
        ServerWebExchange mutated = exchange.mutate().request(request).build();
        mutated.getAttributes().put(ATTRIBUTE, correlationId);
        return chain.filter(mutated);
    }

    public static String of(ServerWebExchange exchange) {
        String id = exchange.getAttribute(ATTRIBUTE);
        return id != null ? id : exchange.getResponse().getHeaders().getFirst(CorrelationId.HEADER);
    }
}
