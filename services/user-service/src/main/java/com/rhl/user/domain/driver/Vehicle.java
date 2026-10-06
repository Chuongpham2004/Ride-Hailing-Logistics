package com.rhl.user.domain.driver;

import com.rhl.common.id.UuidV7;
import com.rhl.user.domain.DomainException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "vehicles")
public class Vehicle {

    public enum Status { ACTIVE, INACTIVE }

    /** Vietnamese plates after normalization, e.g. {@code 51F12345}, {@code 59X312345}. */
    private static final Pattern PLATE = Pattern.compile("^[0-9]{2}[A-Z]{1,2}[0-9]{0,1}[0-9]{4,5}$");

    @Id
    private UUID id;

    @Column(name = "driver_id", nullable = false)
    private UUID driverId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VehicleType type;

    @Column(name = "plate_number", nullable = false)
    private String plateNumber;

    @Column(nullable = false)
    private String brand;

    @Column(nullable = false)
    private String model;

    @Column(nullable = false)
    private String color;

    @Column(name = "manufacture_year", nullable = false)
    private int manufactureYear;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static Vehicle register(UUID driverId, VehicleType type, String plateNumber, String brand, String model,
                                   String color, int manufactureYear, Instant now) {
        Vehicle vehicle = new Vehicle();
        vehicle.id = UuidV7.random();
        vehicle.driverId = driverId;
        vehicle.type = type;
        vehicle.plateNumber = normalizePlate(plateNumber);
        vehicle.brand = brand.strip();
        vehicle.model = model.strip();
        vehicle.color = color.strip();
        vehicle.manufactureYear = manufactureYear;
        vehicle.status = Status.ACTIVE;
        vehicle.createdAt = now;
        vehicle.updatedAt = now;
        return vehicle;
    }

    /** Upper-cases and strips separators so {@code "51F-123.45"} and {@code "51f12345"} match (FR-DRV). */
    public static String normalizePlate(String raw) {
        String value = raw.toUpperCase(Locale.ROOT).replaceAll("[\\s.-]", "");
        if (!PLATE.matcher(value).matches()) {
            throw DomainException.rule("Plate number is not valid");
        }
        return value;
    }

    public void deactivate(Instant now) {
        status = Status.INACTIVE;
        updatedAt = now;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }
}
