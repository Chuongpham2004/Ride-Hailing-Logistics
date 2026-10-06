package com.rhl.user.infrastructure.messaging;

import com.rhl.user.UserServiceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Topics user-service produces or consumes (README §4.7). The broker does not auto-create
 * topics, and the dead-letter recoverer writes to the same partition as the failed record, so
 * every DLT gets as many partitions as its source topic.
 */
@Configuration(proxyBeanMethods = false)
public class Topics {

    public static final String DRIVER_EVENTS = "driver.events.v1";
    public static final String TRIP_EVENTS = "trip.events.v1";
    public static final String DISPATCH_OFFERS = "dispatch.offers.v1";

    @Bean
    public KafkaAdmin.NewTopics userTopics(UserServiceProperties properties) {
        int partitions = properties.kafka().partitions();
        short replicas = properties.kafka().replicas();
        return new KafkaAdmin.NewTopics(
                topic(DRIVER_EVENTS, partitions, replicas).build(),
                // Owned by trip-service; declared here too so start-up order does not matter.
                topic(TRIP_EVENTS, partitions, replicas).build(),
                topic(TRIP_EVENTS + ".DLT", partitions, replicas).build(),
                topic(DISPATCH_OFFERS, partitions, replicas).build(),
                topic(DISPATCH_OFFERS + ".DLT", partitions, replicas).build());
    }

    private static TopicBuilder topic(String name, int partitions, short replicas) {
        return TopicBuilder.name(name).partitions(partitions).replicas(replicas);
    }
}
