package com.rhl.trip.infrastructure.client;

import com.rhl.trip.TripServiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Service token from user-service's OAuth 2.0 token endpoint (client credentials grant, client
 * authenticated with HTTP Basic). One token is shared by every call and replaced shortly before it
 * expires. When user-service cannot be reached, a token that has not expired yet keeps being
 * used, and new attempts are spaced out so a down user-service is not flooded.
 */
@Component
public class ClientCredentialsTokenSource implements ServiceTokenSource {

    private static final Logger log = LoggerFactory.getLogger(ClientCredentialsTokenSource.class);
    static final Duration FAILURE_BACKOFF = Duration.ofSeconds(5);

    private final RestClient rest;
    private final TripServiceProperties.ServiceAuth config;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    private record Cached(String value, Instant refreshAt, Instant expiresAt) {
    }

    private volatile Cached cached;
    private volatile Instant nextAttempt = Instant.MIN;

    public ClientCredentialsTokenSource(RestClient.Builder builder, TripServiceProperties properties, Clock clock) {
        this.config = properties.serviceAuth();
        this.clock = clock;
        HttpClient http = HttpClient.newBuilder().connectTimeout(config.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(config.readTimeout());
        this.rest = builder.requestFactory(factory).build();
    }

    @Override
    public String token() throws IOException {
        Cached current = cached;
        if (current != null && clock.instant().isBefore(current.refreshAt())) {
            return current.value();
        }
        lock.lock();
        try {
            current = cached;
            Instant now = clock.instant();
            if (current != null && now.isBefore(current.refreshAt())) {
                return current.value();
            }
            boolean stillValid = current != null && now.isBefore(current.expiresAt());
            if (now.isBefore(nextAttempt)) {
                if (stillValid) {
                    return current.value();
                }
                throw new IOException("No service token: the user-service token endpoint failed moments ago");
            }
            try {
                Cached fresh = fetch(now);
                cached = fresh;
                return fresh.value();
            } catch (RestClientException | IllegalStateException e) {
                nextAttempt = now.plus(FAILURE_BACKOFF);
                if (stillValid) {
                    log.warn("Could not renew the service token, using the current one until {}: {}",
                            current.expiresAt(), e.getMessage());
                    return current.value();
                }
                throw new IOException("No service token from user-service: " + e.getMessage(), e);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void invalidate(String token) {
        Cached current = cached;
        if (current != null && current.value().equals(token)) {
            cached = null;
        }
    }

    private Cached fetch(Instant now) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("scope", String.join(" ", config.scopes()));
        JsonNode body = rest.post()
                .uri(config.tokenUri())
                // RFC 6749 §2.3.1: client ID and secret are form-encoded before the Basic encoding.
                .headers(h -> h.setBasicAuth(formEncode(config.clientId()), formEncode(config.clientSecret())))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);
        String value = body == null ? "" : body.path("access_token").asString("");
        long lifetime = body == null ? 0 : body.path("expires_in").asLong(0);
        if (value.isBlank() || lifetime <= 0) {
            throw new IllegalStateException("token endpoint answered without access_token or expires_in");
        }
        Duration life = Duration.ofSeconds(lifetime);
        // Renew refreshBefore ahead of expiry, but never later than half-way through a short-lived token.
        Duration renewAfter = life.minus(config.refreshBefore());
        if (renewAfter.compareTo(life.dividedBy(2)) < 0) {
            renewAfter = life.dividedBy(2);
        }
        return new Cached(value, now.plus(renewAfter), now.plus(life));
    }

    private static String formEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
