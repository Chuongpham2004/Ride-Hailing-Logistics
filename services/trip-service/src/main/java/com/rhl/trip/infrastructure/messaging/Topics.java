package com.rhl.trip.infrastructure.messaging;

import com.rhl.trip.TripServiceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/** Topics owned by trip-service (README §4.7). */
@Configuration(proxyBeanMethods = false)
public class Topics {

    /** Key = tripId: every event of one trip stays ordered. */
    public static final String TRIP_EVENTS = "trip.events.v1";
    /** Key = driverId: realtime-gateway delivers offers to the driver's session in order. */
    public static final String DISPATCH_OFFERS = "dispatch.offers.v1";

    @Bean
    public KafkaAdmin.NewTopics tripTopics(TripServiceProperties properties) {
        int partitions = properties.kafka().partitions();
        short replicas = properties.kafka().replicas();
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name(TRIP_EVENTS).partitions(partitions).replicas(replicas).build(),
                TopicBuilder.name(DISPATCH_OFFERS).partitions(partitions).replicas(replicas).build());
    }
}
