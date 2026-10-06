package com.rhl.location.infrastructure.persistence;

import com.rhl.location.domain.Availability;
import com.rhl.location.domain.DriverPresence;
import com.rhl.location.domain.ServiceType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** {@code driver_presence}: the availability projection Redis is rebuilt from (DR-GEO-005). */
@Repository
@RequiredArgsConstructor
public class DriverPresenceRepository {

    private static final String COLUMNS =
            "driver_id, availability, service_types, vehicle_id, aggregate_version, changed_at";

    private static final RowMapper<DriverPresence> MAPPER = (rs, i) -> new DriverPresence(
            rs.getObject("driver_id", UUID.class),
            Availability.valueOf(rs.getString("availability")),
            parseTypes(rs.getString("service_types")),
            rs.getObject("vehicle_id", UUID.class),
            rs.getLong("aggregate_version"),
            rs.getTimestamp("changed_at").toInstant());

    private final JdbcTemplate jdbc;

    public Optional<DriverPresence> find(UUID driverId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM driver_presence WHERE driver_id = ?", MAPPER, driverId)
                .stream().findFirst();
    }

    public Map<UUID, DriverPresence> findAll(Collection<UUID> driverIds) {
        if (driverIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM driver_presence WHERE driver_id = ANY (?)", MAPPER,
                        (Object) driverIds.toArray(UUID[]::new))
                .stream()
                .collect(Collectors.toMap(DriverPresence::driverId, Function.identity()));
    }

    public List<DriverPresence> findByAvailability(Availability availability) {
        return jdbc.query("SELECT " + COLUMNS + " FROM driver_presence WHERE availability = ?", MAPPER,
                availability.name());
    }

    /**
     * Stores the presence unless a newer version is already there (FR-EVT-006).
     *
     * @return whether the row was written
     */
    public boolean saveIfNewer(DriverPresence presence, Instant now) {
        return jdbc.update("""
                        INSERT INTO driver_presence (driver_id, availability, service_types, vehicle_id,
                                                     aggregate_version, changed_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (driver_id) DO UPDATE SET
                            availability = EXCLUDED.availability,
                            service_types = EXCLUDED.service_types,
                            vehicle_id = EXCLUDED.vehicle_id,
                            aggregate_version = EXCLUDED.aggregate_version,
                            changed_at = EXCLUDED.changed_at,
                            updated_at = EXCLUDED.updated_at
                        WHERE driver_presence.aggregate_version < EXCLUDED.aggregate_version
                        """,
                presence.driverId(), presence.availability().name(), formatTypes(presence.serviceTypes()),
                presence.vehicleId(), presence.aggregateVersion(), Timestamp.from(presence.changedAt()),
                Timestamp.from(now)) == 1;
    }

    private static String formatTypes(Set<ServiceType> types) {
        return types.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    private static Set<ServiceType> parseTypes(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(ServiceType::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(ServiceType.class)));
    }
}
