package com.rhl.pricing.application;

import com.rhl.common.id.UuidV7;
import com.rhl.common.security.CurrentUser;
import com.rhl.common.security.Role;
import com.rhl.common.web.ApiException;
import com.rhl.pricing.PricingServiceProperties;
import com.rhl.pricing.domain.DomainException;
import com.rhl.pricing.domain.FareBreakdown;
import com.rhl.pricing.domain.FareCalculator;
import com.rhl.pricing.domain.FareQuote;
import com.rhl.pricing.domain.PricingRule;
import com.rhl.pricing.domain.RouteEstimate;
import com.rhl.pricing.domain.ServiceType;
import com.rhl.pricing.domain.Stop;
import com.rhl.pricing.domain.SurgeAssessment;
import com.rhl.pricing.infrastructure.cache.QuoteCache;
import com.rhl.pricing.infrastructure.persistence.FareQuoteRepository;
import com.rhl.pricing.infrastructure.persistence.PricingRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Quotes for customers (FR-PRI, UC-02) and their validation for trip-service (BR-005). */
@Service
@RequiredArgsConstructor
public class QuoteService {

    private final PricingRuleRepository rules;
    private final FareQuoteRepository quotes;
    private final RouteProvider routes;
    private final SurgeProvider surge;
    private final QuoteCache cache;
    private final PricingServiceProperties properties;
    private final Clock clock;

    public record QuoteCommand(ServiceType serviceType, Stop pickup, Stop dropoff) {
    }

    @Transactional
    public PricingViews.QuoteView create(UUID customerId, QuoteCommand command) {
        Instant now = clock.instant();
        PricingServiceProperties.Quote config = properties.quote();
        RouteEstimate route = routes.route(command.serviceType(), command.pickup(), command.dropoff());
        if (route.distanceMeters() < config.minDistanceMeters()) {
            throw DomainException.rule("Pickup and drop-off are too close (under " + config.minDistanceMeters()
                    + " m)");
        }
        if (route.distanceMeters() > config.maxDistanceMeters()) {
            throw DomainException.rule("The trip is longer than " + config.maxDistanceMeters() / 1000
                    + " km, which is not served");
        }
        PricingRule rule = rules.findEffective(command.serviceType(), properties.regionCode(), now)
                .orElseThrow(() -> DomainException.rule("No price is configured for " + command.serviceType()));
        SurgeAssessment assessment = surge.assess(command.serviceType(), command.pickup(), now);
        FareBreakdown fare = FareCalculator.calculate(rule.tariff(), route, assessment.multiplier(),
                config.roundingStep());

        FareQuote quote = FareQuote.issue(UuidV7.random(), customerId, command.serviceType(), command.pickup(),
                command.dropoff(), route, rule, assessment, fare, config.ttl(), now);
        quotes.save(quote);
        PricingViews.QuoteView view = PricingViews.QuoteView.of(quote);
        // Cached only once committed, so trip-service never sees a quote the database does not have.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cache.put(view, now);
            }
        });
        return view;
    }

    /** The owner, or an administrator; anyone else gets 404 so quote IDs cannot be probed. */
    @Transactional(readOnly = true)
    public PricingViews.QuoteView get(CurrentUser user, UUID quoteId) {
        PricingViews.QuoteView quote = load(quoteId);
        if (!quote.customerId().equals(user.id()) && !user.has(Role.ADMINISTRATOR)) {
            throw ApiException.notFound("Quote");
        }
        return quote;
    }

    /**
     * For trip-service (README §5.1): the quote must belong to {@code customerId}, still be valid
     * and, when {@code serviceType} is given, be for that service.
     */
    @Transactional(readOnly = true)
    public PricingViews.QuoteView validateFor(UUID quoteId, UUID customerId, ServiceType serviceType) {
        PricingViews.QuoteView quote = load(quoteId);
        if (!quote.customerId().equals(customerId)) {
            throw ApiException.notFound("Quote");
        }
        if (!clock.instant().isBefore(quote.expiresAt())) {
            throw DomainException.quoteExpired("The quote expired at " + quote.expiresAt() + ", ask for a new one");
        }
        if (serviceType != null && quote.serviceType() != serviceType) {
            throw DomainException.rule("The quote is for " + quote.serviceType() + ", not " + serviceType);
        }
        return quote;
    }

    private PricingViews.QuoteView load(UUID quoteId) {
        return cache.get(quoteId).orElseGet(() -> quotes.findById(quoteId)
                .map(PricingViews.QuoteView::of)
                .orElseThrow(() -> ApiException.notFound("Quote")));
    }
}
