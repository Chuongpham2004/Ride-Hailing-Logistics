package com.rhl.location.application;

import com.rhl.location.domain.LatestLocation;
import com.rhl.location.domain.TelemetryPolicy;
import com.rhl.location.domain.TelemetryQuality;
import com.rhl.location.domain.TelemetryRejectedException;
import com.rhl.location.domain.TelemetryReport;
import com.rhl.location.infrastructure.cache.LocationStore;
import com.rhl.location.infrastructure.messaging.LocationUpdatePublisher;
import com.rhl.location.infrastructure.persistence.DriverPresenceRepository;
import com.rhl.location.infrastructure.persistence.TelemetryHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * One pipeline for every report, whether it came over Kafka (realtime-gateway) or HTTP:
 * validate, apply atomically in Redis, keep history, publish the new current position.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelemetryService {

    /** What happened to a report; {@code DUPLICATE} reports are not stored anywhere. */
    public enum Outcome {
        CURRENT, BACKFILL, OUT_OF_ORDER, LOW_ACCURACY, SUSPICIOUS, DUPLICATE
    }

    private final DriverPresenceRepository presences;
    private final LocationStore store;
    private final TelemetryHistoryRepository history;
    private final LocationUpdatePublisher publisher;
    private final TelemetryPolicy policy;
    private final Clock clock;

    /** @throws TelemetryRejectedException when the report is unusable or the driver is offline */
    public Outcome ingest(TelemetryReport report, TelemetryHistoryRepository.Source source) {
        boolean online = presences.find(report.driverId()).map(p -> !p.isOffline()).orElse(false);
        if (!online) {
            throw new TelemetryRejectedException(TelemetryRejectedException.Reason.DRIVER_OFFLINE,
                    "Location is only accepted while the driver is online");
        }

        Instant now = clock.instant();
        LatestLocation previous = store.latest(report.driverId()).orElse(null);
        TelemetryQuality quality = policy.assess(report, previous, now);

        LocationStore.ApplyResult result = store.apply(report, quality.isCurrent(), now);
        if (result == LocationStore.ApplyResult.DUPLICATE) {
            return Outcome.DUPLICATE;
        }
        try {
            history.append(report, quality, now, source);
        } catch (RuntimeException e) {
            // History is best effort; it must never hold back the live position.
            log.warn("Could not store telemetry history for sequence {}: {}", report.sequence(), e.getMessage());
        }
        if (result == LocationStore.ApplyResult.CURRENT) {
            publisher.publish(LatestLocation.of(report, now));
        }
        return Outcome.valueOf(quality.name());
    }
}
