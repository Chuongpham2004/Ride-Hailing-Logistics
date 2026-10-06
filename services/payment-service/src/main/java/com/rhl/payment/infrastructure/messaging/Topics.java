package com.rhl.payment.infrastructure.messaging;

import com.rhl.common.messaging.MessagingAutoConfiguration;
import com.rhl.payment.PaymentServiceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/** Topics payment-service produces or consumes (README §4.7). */
@Configuration(proxyBeanMethods = false)
public class Topics {

    /** Owned by pricing-service; declared here too so start-up order does not matter. */
    public static final String PRICING_EVENTS = "pricing.events.v1";
    /** Key = tripId. */
    public static final String PAYMENT_EVENTS = "payment.events.v1";
    /** Key = driverId. */
    public static final String WALLET_EVENTS = "wallet.events.v1";

    @Bean
    public KafkaAdmin.NewTopics paymentTopics(PaymentServiceProperties properties) {
        int partitions = properties.kafka().partitions();
        short replicas = properties.kafka().replicas();
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name(PRICING_EVENTS).partitions(partitions).replicas(replicas).build(),
                TopicBuilder.name(MessagingAutoConfiguration.deadLetterTopic(PRICING_EVENTS)).partitions(partitions)
                        .replicas(replicas).build(),
                TopicBuilder.name(PAYMENT_EVENTS).partitions(partitions).replicas(replicas).build(),
                TopicBuilder.name(WALLET_EVENTS).partitions(partitions).replicas(replicas).build());
    }
}
