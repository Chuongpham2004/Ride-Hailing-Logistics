package com.rhl.location.infrastructure.persistence;

import com.rhl.location.LocationServiceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Keeps one {@code telemetry_history} partition per UTC day: creates today's and the next two,
 * drops those past the retention period (TBD-12). Statements are idempotent, so several
 * instances can run this at the same time.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelemetryPartitions {

    private static final String PREFIX = "telemetry_history_p";
    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.BASIC_ISO_DATE;
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
        dropBefore(today.minusDays(properties.telemetry().historyRetentionDays()));
    }

    private void create(LocalDate day) {
        // Names and bounds come from LocalDate formatting only, never from input.
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS " + PREFIX + day.format(SUFFIX)
                    + " PARTITION OF telemetry_history FOR VALUES FROM ('" + day + " 00:00:00+00') TO ('"
                    + day.plusDays(1) + " 00:00:00+00')");
        } catch (RuntimeException e) {
            // Another instance created it a moment earlier; the next run checks again.
            log.warn("Could not create telemetry partition for {}: {}", day, e.getMessage());
        }
    }

    private void dropBefore(LocalDate oldestKept) {
        List<String> partitions = jdbc.queryForList("""
                SELECT c.relname FROM pg_inherits i
                JOIN pg_class c ON c.oid = i.inhrelid
                JOIN pg_class p ON p.oid = i.inhparent
                WHERE p.relname = 'telemetry_history'
                """, String.class);
        for (String name : partitions) {
            if (!name.startsWith(PREFIX)) {
                continue;
            }
            LocalDate day;
            try {
                day = LocalDate.parse(name.substring(PREFIX.length()), SUFFIX);
            } catch (RuntimeException e) {
                continue;
            }
            if (day.isBefore(oldestKept)) {
                jdbc.execute("DROP TABLE IF EXISTS " + PREFIX + day.format(SUFFIX));
                log.info("Dropped telemetry partition {} (retention {} days)", name,
                        properties.telemetry().historyRetentionDays());
            }
        }
    }
}
