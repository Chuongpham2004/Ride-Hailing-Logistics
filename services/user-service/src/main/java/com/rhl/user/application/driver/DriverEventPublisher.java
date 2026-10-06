package com.rhl.user.application.driver;

import com.rhl.common.messaging.OutboxWriter;
import com.rhl.user.domain.driver.AvailabilityChange;
import com.rhl.user.domain.driver.ServiceType;
import com.rhl.user.infrastructure.messaging.Topics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Maps driver domain changes to events in {@code contracts/events/driver}. */
@Component
@RequiredArgsConstructor
public class DriverEventPublisher {

    private final OutboxWriter outbox;

    /** @param aggregateVersion the profile version after the change, flushed to the database */
    public void availabilityChanged(AvailabilityChange change, long aggregateVersion) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("driverId", change.driverId().toString());
        payload.put("oldStatus", change.oldStatus().name());
        payload.put("newStatus", change.newStatus().name());
        payload.put("serviceTypes", change.serviceTypes().stream().map(ServiceType::name).sorted().toList());
        payload.put("vehicleId", change.vehicleId() == null ? null : change.vehicleId().toString());
        payload.put("reason", change.reason());
        payload.put("occurredAt", change.occurredAt().toString());

        String driverId = change.driverId().toString();
        outbox.append(Topics.DRIVER_EVENTS, driverId, "DriverAvailabilityChanged", 1, driverId, aggregateVersion,
                payload);
    }
}
