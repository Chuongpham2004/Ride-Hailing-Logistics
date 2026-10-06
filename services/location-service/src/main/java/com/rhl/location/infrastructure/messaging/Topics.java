package com.rhl.location.infrastructure.messaging;

import com.rhl.location.LocationServiceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import java.time.Duration;

/**
 * Topics location-service produces or consumes (README §4.7). The broker does not auto-create
 * topics, and the dead-letter recoverer writes to the same partition as the failed record, so
 * every DLT gets as many partitions as its source topic.
 */
@Configuration(proxyBeanMethods = false)
public class Topics {

    public static final String DRIVER_EVENTS = "driver.events.v1";
    public static final String TELEMETRY_RAW = "location.telemetry.raw.v1";
    public static final String LOCATION_UPDATES = "location.updates.v1";

    /** Raw and validated positions are short-lived (README §4.7): keep them for a day at most. */
    private static final String SHORT_RETENTION_MS = Long.toString(Duration.ofDays(1).toMillis());

    @Bean
    public KafkaAdmin.NewTopics locationTopics(LocationServiceProperties properties) {
        int partitions = properties.kafka().partitions();
        short replicas = properties.kafka().replicas();
        return new KafkaAdmin.NewTopics(
                // Owned by user-service; declared here too so start-up order does not matter.
                topic(DRIVER_EVENTS, partitions, replicas).build(),
                topic(DRIVER_EVENTS + ".DLT", partitions, replicas).build(),
                topic(TELEMETRY_RAW, partitions, replicas).config("retention.ms", SHORT_RETENTION_MS).build(),
                topic(TELEMETRY_RAW + ".DLT", partitions, replicas).build(),
                topic(LOCATION_UPDATES, partitions, replicas).config("retention.ms", SHORT_RETENTION_MS).build());
    }

    private static TopicBuilder topic(String name, int partitions, short replicas) {
        return TopicBuilder.name(name).partitions(partitions).replicas(replicas);
    }
}
