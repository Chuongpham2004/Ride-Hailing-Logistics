package com.rhl.common.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code rhl.outbox.*}. The producer name defaults to {@code spring.application.name}.
 *
 * @param enabled      run the relay in this instance
 * @param batchSize    rows claimed per poll ({@code FOR UPDATE SKIP LOCKED})
 * @param pollInterval delay between polls
 * @param sendTimeout  max wait for the broker ack of one record
 */
@ConfigurationProperties("rhl.outbox")
public record OutboxProperties(Boolean enabled, Integer batchSize, Duration pollInterval, Duration sendTimeout) {

    public OutboxProperties {
        enabled = enabled == null || enabled;
        batchSize = batchSize == null ? 100 : batchSize;
        pollInterval = pollInterval == null ? Duration.ofMillis(200) : pollInterval;
        sendTimeout = sendTimeout == null ? Duration.ofSeconds(10) : sendTimeout;
    }
}
