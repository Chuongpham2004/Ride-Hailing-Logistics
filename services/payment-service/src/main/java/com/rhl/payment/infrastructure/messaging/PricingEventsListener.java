package com.rhl.payment.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.rhl.common.messaging.EventEnvelope;
import com.rhl.common.web.CorrelationId;
import com.rhl.payment.application.PaymentProcessor;
import com.rhl.payment.application.PaymentSteps;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes pricing.events.v1 (README §4.9): {@code FareFinalized} and
 * {@code CancellationFeeCalculated} open a payment, which is then charged right away. If the
 * charge cannot complete here, the unresolved attempt is picked up by {@link PaymentProcessor}.
 */
@Component
@RequiredArgsConstructor
public class PricingEventsListener {

    private final EventReader reader;
    private final PaymentSteps steps;
    private final PaymentProcessor processor;

    @KafkaListener(id = "payment-pricing-events", idIsGroup = false, topics = Topics.PRICING_EVENTS)
    public void onMessage(ConsumerRecord<String, String> record) throws JsonProcessingException {
        String type = reader.eventType(record.value());
        if (!"FareFinalized".equals(type) && !"CancellationFeeCalculated".equals(type)) {
            return;
        }
        EventEnvelope event = reader.read(record.value());
        MDC.put(CorrelationId.MDC_KEY, event.correlationId());
        try {
            steps.open(event).ifPresent(processor::charge);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}
