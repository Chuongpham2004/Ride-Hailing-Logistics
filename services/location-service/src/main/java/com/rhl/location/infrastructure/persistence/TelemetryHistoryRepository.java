package com.rhl.location.infrastructure.persistence;

import com.rhl.location.domain.TelemetryQuality;
import com.rhl.location.domain.TelemetryReport;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/** Append-only {@code telemetry_history}; rows leave only when a whole day's partition is dropped. */
@Repository
@RequiredArgsConstructor
public class TelemetryHistoryRepository {

    public enum Source {
        /** location.telemetry.raw.v1, published by realtime-gateway. */
        STREAM,
        /** POST /api/v1/locations/me. */
        HTTP
    }

    private final JdbcTemplate jdbc;

    public void append(TelemetryReport report, TelemetryQuality quality, Instant receivedAt, Source source) {
        jdbc.update("""
                        INSERT INTO telemetry_history (driver_id, sequence, latitude, longitude, accuracy_meters,
                                                       heading_degrees, speed_meters_per_second, device_time,
                                                       received_at, quality, source)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                report.driverId(), report.sequence(), report.latitude(), report.longitude(),
                report.accuracyMeters(), report.headingDegrees(), report.speedMetersPerSecond(),
                Timestamp.from(report.deviceTime()), Timestamp.from(receivedAt), quality.name(), source.name());
    }
}
