package com.rhl.user.infrastructure.messaging;

import com.rhl.user.UserServiceProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Topics owned by user-service (README §4.7). */
@Configuration(proxyBeanMethods = false)
public class Topics {

    public static final String DRIVER_EVENTS = "driver.events.v1";

    @Bean
    public NewTopic driverEventsTopic(UserServiceProperties properties) {
        return TopicBuilder.name(DRIVER_EVENTS)
                .partitions(properties.kafka().partitions())
                .replicas(properties.kafka().replicas())
                .build();
    }
}
