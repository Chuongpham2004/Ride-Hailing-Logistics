package com.rhl.gateway;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.Optional;

/** Token-bucket rate limiting in Redis (NFR-SEC-007), per user when authenticated, per client IP otherwise. */
@Configuration(proxyBeanMethods = false)
public class RateLimitConfiguration {

    @Bean
    public KeyResolver principalOrIpKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .filter(Authentication.class::isInstance)
                .map(p -> "user:" + p.getName())
                .switchIfEmpty(Mono.fromSupplier(() -> "ip:" + Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                        .map(InetSocketAddress::getAddress)
                        .map(a -> a.getHostAddress())
                        .orElse("unknown")));
    }
}
