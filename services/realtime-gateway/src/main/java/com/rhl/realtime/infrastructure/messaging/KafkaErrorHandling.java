package com.rhl.realtime.infrastructure.messaging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Pushing to clients is best effort: a record that cannot be read or delivered is logged and
 * skipped, never retried or dead-lettered. The owning services keep the source of truth and
 * clients recover from their REST snapshot (FR-RT, UC-07). Each instance reads with its own
 * consumer group, so dead-lettering here would copy every bad record once per instance.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class KafkaErrorHandling {

    @Bean
    public CommonErrorHandler realtimeKafkaErrorHandler() {
        return new DefaultErrorHandler((record, exception) -> log.warn("Skipped {}-{}@{}: {}", record.topic(),
                record.partition(), record.offset(), exception.getMessage()), new FixedBackOff(0, 0));
    }
}
