package com.rhl.trip.infrastructure.client;

import com.rhl.trip.TripServiceProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.function.Supplier;

/**
 * Circuit breaker plus bounded retry around the calls to one remote service (README §2, §8.5).
 * Only network errors and 5xx count as failures and are retried; a 4xx is the remote's answer
 * and passes through untouched. While the circuit is open, calls fail at once with
 * {@link io.github.resilience4j.circuitbreaker.CallNotPermittedException}.
 */
@Slf4j
public final class RemoteCalls {

    private final CircuitBreaker breaker;
    private final Retry retry;

    public RemoteCalls(String name, TripServiceProperties.Resilience config) {
        this.breaker = CircuitBreaker.of(name, CircuitBreakerConfig.custom()
                .failureRateThreshold(config.failureRateThreshold())
                .slidingWindowSize(config.slidingWindowSize())
                .minimumNumberOfCalls(config.minimumCalls())
                .waitDurationInOpenState(config.openFor())
                .recordException(RemoteCalls::isTransient)
                .build());
        this.retry = Retry.of(name, RetryConfig.custom()
                .maxAttempts(config.maxAttempts())
                .waitDuration(config.retryWait())
                .retryOnException(RemoteCalls::isTransient)
                .build());
        breaker.getEventPublisher().onStateTransition(event ->
                log.warn("Circuit to {} is now {}", name, event.getStateTransition().getToState()));
    }

    /** For idempotent reads: retried, then counted by the breaker. */
    public <T> T read(Supplier<T> call) {
        return Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(breaker, call)).get();
    }

    public CircuitBreaker.State state() {
        return breaker.getState();
    }

    static boolean isTransient(Throwable error) {
        return error instanceof ResourceAccessException || error instanceof HttpServerErrorException;
    }
}
