package com.rhl.pricing.infrastructure.cache;

import com.rhl.pricing.application.PricingViews;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code quote:{quoteId}} (README §4.6): read path for trip-service's validation. The table stays
 * the source of truth, so a Redis failure only means a database read, never a refused quote.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuoteCache {

    private static final String PREFIX = "quote:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public void put(PricingViews.QuoteView quote, Instant now) {
        Duration ttl = Duration.between(now, quote.expiresAt());
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        try {
            redis.opsForValue().set(PREFIX + quote.id(), objectMapper.writeValueAsString(quote), ttl);
        } catch (JacksonException | DataAccessException e) {
            log.warn("Could not cache quote: {}", e.getMessage());
        }
    }

    public Optional<PricingViews.QuoteView> get(UUID quoteId) {
        try {
            String json = redis.opsForValue().get(PREFIX + quoteId);
            return json == null ? Optional.empty()
                    : Optional.of(objectMapper.readValue(json, PricingViews.QuoteView.class));
        } catch (JacksonException | DataAccessException e) {
            log.warn("Could not read cached quote: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
