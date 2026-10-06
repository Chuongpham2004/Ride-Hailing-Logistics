package com.rhl.location.application;

import com.rhl.location.LocationServiceProperties;
import com.rhl.location.domain.ServiceType;
import com.rhl.location.infrastructure.cache.LocationStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/** Takes drivers who stopped reporting out of the GEO index (FR-LOC-008, signal loss). */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleLocationPruner {

    private final LocationStore store;
    private final LocationServiceProperties properties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${rhl.telemetry.cleanup-interval}")
    public void prune() {
        Instant cutoff = clock.instant().minus(properties.telemetry().locationTtl());
        for (ServiceType type : ServiceType.values()) {
            try {
                long removed = store.pruneStale(type, cutoff);
                if (removed > 0) {
                    log.info("Removed {} stale driver(s) from the {} index", removed, type);
                }
            } catch (RuntimeException e) {
                log.warn("Could not prune the {} index: {}", type, e.getMessage());
            }
        }
    }
}
