package com.rhl.pricing.infrastructure.messaging;

import com.rhl.pricing.PricingServiceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import java.time.Duration;

/**
 * Topics pricing-service produces or consumes (README §4.7); consumed ones are declared here too
 * so start-up order does not matter. The dead-letter recoverer writes to the same partition as the failed record, so
 * every DLT gets as many partitions as its source topic.
 */
@Configuration(proxyBeanMethods = false)
public class Topics {

    public static final String TRIP_EVENTS = "trip.events.v1";
    public static final String DRIVER_EVENTS = "driver.events.v1";
    public static final String LOCATION_UPDATES = "location.updates.v1";
    /** Owned by pricing-service; key = tripId. */
    public static final String PRICING_EVENTS = "pricing.events.v1";

    /** Same retention as location-service declares for its short-lived position stream. */
    private static final String SHORT_RETENTION_MS = Long.toString(Duration.ofDays(1).toMillis());

    @Bean
    public KafkaAdmin.NewTopics pricingTopics(PricingServiceProperties properties) {
        int partitions = properties.kafka().partitions();
        short replicas = properties.kafka().replicas();
        return new KafkaAdmin.NewTopics(
                topic(TRIP_EVENTS, partitions, replicas).build(),
                topic(TRIP_EVENTS + ".DLT", partitions, replicas).build(),
                topic(DRIVER_EVENTS, partitions, replicas).build(),
                topic(DRIVER_EVENTS + ".DLT", partitions, replicas).build(),
                topic(LOCATION_UPDATES, partitions, replicas).config("retention.ms", SHORT_RETENTION_MS).build(),
                topic(LOCATION_UPDATES + ".DLT", partitions, replicas).build(),
                topic(PRICING_EVENTS, partitions, replicas).build());
    }

    private static TopicBuilder topic(String name, int partitions, short replicas) {
        return TopicBuilder.name(name).partitions(partitions).replicas(replicas);
    }
}
