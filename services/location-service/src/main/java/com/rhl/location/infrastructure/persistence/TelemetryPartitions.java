package com.rhl.location.infrastructure.persistence;

import com.rhl.location.LocationServiceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Keeps one {@code telemetry_history} partition per UTC day: creates today's and the next two,
 * drops those past the retention period (TBD-12). The DDL lives in database functions (V2
 * migration) that take dates as bind parameters. Both functions are idempotent, so several
 * instances can run this at the same time.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelemetryPartitions {

    private static final int DAYS_AHEAD = 2;

    private final JdbcTemplate jdbc;
    private final LocationServiceProperties properties;
    private final Clock clock;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        maintain();
    }

    @Scheduled(cron = "0 5 * * * *", zone = "UTC")
    public void maintain() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        for (int i = 0; i <= DAYS_AHEAD; i++) {
            create(today.plusDays(i));
        }
        int retentionDays = properties.telemetry().historyRetentionDays();
        Integer dropped = jdbc.queryForObject("SELECT drop_telemetry_partitions_before(?)", Integer.class,
                Date.valueOf(today.minusDays(retentionDays)));
        if (dropped != null && dropped > 0) {
            log.info("Dropped {} telemetry partition(s) older than {} days", dropped, retentionDays);
        }
    }

    private void create(LocalDate day) {
        try {
            jdbc.queryForObject("SELECT ensure_telemetry_partition(?)", Object.class, Date.valueOf(day));
        } catch (RuntimeException e) {
            // Another instance created it a moment earlier; the next run checks again.
            log.warn("Could not create telemetry partition for {}: {}", day, e.getMessage());
        }
    }
}
