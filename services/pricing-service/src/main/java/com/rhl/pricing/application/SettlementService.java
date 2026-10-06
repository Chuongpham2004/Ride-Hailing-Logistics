package com.rhl.pricing.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.rhl.common.id.UuidV7;
import com.rhl.common.messaging.EventEnvelope;
import com.rhl.common.messaging.InvalidEventException;
import com.rhl.common.messaging.OutboxWriter;
import com.rhl.common.messaging.ProcessedEvents;
import com.rhl.pricing.domain.CancellationFee;
import com.rhl.pricing.domain.CancellationFeeRule;
import com.rhl.pricing.domain.FareBreakdown;
import com.rhl.pricing.domain.FareQuote;
import com.rhl.pricing.domain.FinalFare;
import com.rhl.pricing.domain.ServiceType;
import com.rhl.pricing.infrastructure.messaging.Topics;
import com.rhl.pricing.infrastructure.persistence.CancellationFeeRepository;
import com.rhl.pricing.infrastructure.persistence.CancellationFeeRuleRepository;
import com.rhl.pricing.infrastructure.persistence.FareQuoteRepository;
import com.rhl.pricing.infrastructure.persistence.FinalFareRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Settles trips once they end (README §4.9): {@code TripCompleted} → final fare →
 * {@code FareFinalized}; {@code TripCancelled} → fee decision → {@code CancellationFeeCalculated}.
 * Exactly once per trip: processed_events drops redelivered events, UNIQUE (trip_id) drops a
 * second event for the same trip, and the outbox row commits with the settlement row.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementService {

    static final String CONSUMER = "pricing-service.settlement";

    private final ProcessedEvents processedEvents;
    private final FareQuoteRepository quotes;
    private final FinalFareRepository finalFares;
    private final CancellationFeeRepository fees;
    private final CancellationFeeRuleRepository feeRules;
    private final OutboxWriter outbox;
    private final Clock clock;

    @Transactional
    public void onTripCompleted(EventEnvelope event) {
        if (!processedEvents.markProcessed(CONSUMER, event.eventId())) {
            return;
        }
        JsonNode p = event.payload();
        UUID tripId = id(p, "tripId");
        if (finalFares.existsByTripId(tripId)) {
            log.info("Trip already has a final fare; TripCompleted {} ignored", event.eventId());
            return;
        }
        if (!p.hasNonNull("quoteId")) {
            // Booked before quotes were required: there is no price to settle, so it goes to the
            // DLT for someone to handle rather than being charged a made-up amount.
            throw new InvalidEventException("TripCompleted for trip " + tripId + " has no quoteId");
        }
        FareQuote quote = quotes.findById(id(p, "quoteId"))
                .orElseThrow(() -> new InvalidEventException("Quote of trip " + tripId + " does not exist"));
        FinalFare fare;
        try {
            fare = FinalFare.upfront(UuidV7.random(), tripId, id(p, "customerId"), id(p, "driverId"), quote,
                    Instant.parse(p.path("completedAt").asText()), clock.instant());
        } catch (IllegalArgumentException e) {
            throw new InvalidEventException(e.getMessage());
        }
        finalFares.save(fare);
        Map<String, Object> payload = fareFinalized(fare);
        payload.put("serviceType", quote.getServiceType().name());
        outbox.append(Topics.PRICING_EVENTS, tripId.toString(), "FareFinalized", 1, tripId.toString(), 0, payload);
    }

    @Transactional
    public void onTripCancelled(EventEnvelope event) {
        if (!processedEvents.markProcessed(CONSUMER, event.eventId())) {
            return;
        }
        JsonNode p = event.payload();
        UUID tripId = id(p, "tripId");
        if (fees.existsByTripId(tripId)) {
            log.info("Trip already has a cancellation fee; TripCancelled {} ignored", event.eventId());
            return;
        }
        UUID quoteId = p.hasNonNull("quoteId") ? id(p, "quoteId") : null;
        FareQuote quote = quoteId == null ? null : quotes.findById(quoteId).orElse(null);
        ServiceType serviceType = p.hasNonNull("serviceType") ? ServiceType.valueOf(p.path("serviceType").asText())
                : quote != null ? quote.getServiceType() : ServiceType.RIDE;
        Instant cancelledAt = Instant.parse(p.path("cancelledAt").asText());
        CancellationFeeRule rule = feeRules.findEffective(serviceType, cancelledAt)
                .orElseThrow(() -> new IllegalStateException("No cancellation fee rule for " + serviceType));

        CancellationFeeRule.Cancellation cancellation = new CancellationFeeRule.Cancellation(
                p.path("actorType").asText(), p.path("oldStatus").asText(), p.path("reason").asText(),
                p.hasNonNull("acceptedAt") ? Instant.parse(p.path("acceptedAt").asText()) : null, cancelledAt,
                quote == null ? null : quote.getTotal());
        CancellationFee fee = CancellationFee.decide(UuidV7.random(), tripId, id(p, "customerId"),
                p.hasNonNull("driverId") ? id(p, "driverId") : null, quoteId, rule, cancellation, clock.instant());
        fees.save(fee);
        Map<String, Object> payload = cancellationFeeCalculated(fee);
        payload.put("serviceType", serviceType.name());
        outbox.append(Topics.PRICING_EVENTS, tripId.toString(), "CancellationFeeCalculated", 1, tripId.toString(),
                0, payload);
    }

    private static Map<String, Object> fareFinalized(FinalFare fare) {
        FareBreakdown b = fare.breakdown();
        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("baseFare", b.baseFare());
        breakdown.put("distanceFare", b.distanceFare());
        breakdown.put("timeFare", b.timeFare());
        breakdown.put("minimumFareAdjustment", b.minimumFareAdjustment());
        breakdown.put("surgeAmount", b.surgeAmount());
        breakdown.put("roundingAdjustment", b.roundingAdjustment());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fareId", fare.getId().toString());
        payload.put("tripId", fare.getTripId().toString());
        payload.put("customerId", fare.getCustomerId().toString());
        payload.put("driverId", fare.getDriverId().toString());
        payload.put("quoteId", fare.getQuoteId().toString());
        payload.put("method", fare.getMethod());
        payload.put("breakdown", breakdown);
        payload.put("total", fare.getTotal());
        payload.put("currency", fare.getCurrency());
        payload.put("surgeMultiplier", fare.getSurgeMultiplier());
        payload.put("pricingRuleVersion", fare.getPricingRuleVersion());
        payload.put("completedAt", fare.getCompletedAt().toString());
        payload.put("finalizedAt", fare.getFinalizedAt().toString());
        return payload;
    }

    private static Map<String, Object> cancellationFeeCalculated(CancellationFee fee) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("feeId", fee.getId().toString());
        payload.put("tripId", fee.getTripId().toString());
        payload.put("customerId", fee.getCustomerId().toString());
        payload.put("driverId", fee.getDriverId() == null ? null : fee.getDriverId().toString());
        payload.put("decision", fee.getDecision());
        payload.put("fee", fee.getFee());
        payload.put("currency", fee.getCurrency());
        payload.put("ruleVersion", fee.getRuleVersion());
        payload.put("cancelledAt", fee.getCancelledAt().toString());
        payload.put("calculatedAt", fee.getCalculatedAt().toString());
        return payload;
    }

    private static UUID id(JsonNode payload, String field) {
        return UUID.fromString(payload.path(field).asText());
    }
}
