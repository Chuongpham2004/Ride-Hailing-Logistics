package com.rhl.common.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * Wires the outbox, idempotent consumer and Kafka error handling into any service that has a
 * DataSource and Kafka on the classpath.
 */
@AutoConfiguration(after = {KafkaAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
        TransactionAutoConfiguration.class})
@EnableConfigurationProperties(OutboxProperties.class)
public class MessagingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    public EventSchemaValidator eventSchemaValidator(ObjectMapper objectMapper) {
        return new EventSchemaValidator(objectMapper);
    }

    /**
     * Transient failures are retried with backoff (FR-EVT-005); contract violations and anything
     * still failing afterwards go to {@code <topic>.DLT} (FR-EVT-008) instead of blocking the
     * partition.
     */
    @Bean
    @ConditionalOnBean(KafkaOperations.class)
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<?, ?> kafka) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(5);
        backOff.setInitialInterval(500);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10_000);
        // Spring Kafka's default target is "<topic>-dlt"; services declare "<topic>.DLT" (README §4.7)
        // and the broker does not auto-create topics, so name it explicitly. Same partition as the
        // failed record, which is why every DLT has as many partitions as its source topic.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, exception) -> new TopicPartition(deadLetterTopic(record.topic()), record.partition()));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(InvalidEventException.class,
                com.fasterxml.jackson.core.JsonProcessingException.class);
        return handler;
    }

    /** Dead-letter topic of {@code topic}: {@code <topic>.DLT} (README §4.7, FR-EVT-008). */
    public static String deadLetterTopic(String topic) {
        return topic + ".DLT";
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(JdbcTemplate.class)
    static class JdbcMessagingConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public ProcessedEvents processedEvents(JdbcTemplate jdbc, Clock clock) {
            return new ProcessedEvents(jdbc, clock);
        }

        @Bean
        @ConditionalOnMissingBean
        public OutboxWriter outboxWriter(JdbcTemplate jdbc, ObjectMapper objectMapper, EventSchemaValidator validator,
                                         @Value("${spring.application.name}") String producer, Clock clock) {
            return new OutboxWriter(jdbc, objectMapper, validator, producer, clock);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnBean({JdbcTemplate.class, KafkaTemplate.class, TransactionTemplate.class})
    @ConditionalOnProperty(name = "rhl.outbox.enabled", havingValue = "true", matchIfMissing = true)
    static class OutboxRelayConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public OutboxRelay outboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, KafkaTemplate<String, String> kafka,
                                       OutboxProperties properties, Clock clock) {
            return new OutboxRelay(jdbc, tx, kafka, properties, clock);
        }
    }
}
