package com.rhl.realtime.infrastructure.messaging;

import com.rhl.realtime.RealtimeProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/** Topics realtime-gateway produces or consumes (README §4.7). */
@Configuration(proxyBeanMethods = false)
public class Topics {

    /** Produced here, consumed by location-service. Key = driverId. */
    public static final String TELEMETRY_RAW = "location.telemetry.raw.v1";

    // Owned by other services; declared here too so start-up order does not matter.
    public static final String DISPATCH_OFFERS = "dispatch.offers.v1";
    public static final String TRIP_EVENTS = "trip.events.v1";
    public static final String PAYMENT_EVENTS = "payment.events.v1";
    public static final String WALLET_EVENTS = "wallet.events.v1";
    /** Validated driver positions, key = driverId; shown only to the customer of the driver's trip. */
    public static final String LOCATION_UPDATES = "location.updates.v1";

    @Bean
    public KafkaAdmin.NewTopics realtimeTopics(RealtimeProperties properties) {
        int partitions = properties.kafka().partitions();
        short replicas = properties.kafka().replicas();
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name(TELEMETRY_RAW).partitions(partitions).replicas(replicas).build(),
                TopicBuilder.name(DISPATCH_OFFERS).partitions(partitions).replicas(replicas).build(),
                TopicBuilder.name(TRIP_EVENTS).partitions(partitions).replicas(replicas).build(),
                TopicBuilder.name(PAYMENT_EVENTS).partitions(partitions).replicas(replicas).build(),
                TopicBuilder.name(WALLET_EVENTS).partitions(partitions).replicas(replicas).build(),
                TopicBuilder.name(LOCATION_UPDATES).partitions(partitions).replicas(replicas).build());
    }
}
