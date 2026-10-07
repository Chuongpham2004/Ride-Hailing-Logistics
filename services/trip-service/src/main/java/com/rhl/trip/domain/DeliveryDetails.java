package com.rhl.trip.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Recipient and package of a DELIVERY trip, snapshotted at booking (FR-TRIP). Personal data:
 * only the customer, the assigned driver and staff may see it; it never goes into offers or
 * events (BR-013, UC-05).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Immutable
@Table(name = "delivery_details")
public class DeliveryDetails {

    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9]{8,15}$");

    @Id
    @Column(name = "trip_id")
    private UUID tripId;

    @Column(name = "recipient_name", nullable = false)
    private String recipientName;

    @Column(name = "recipient_phone", nullable = false)
    private String recipientPhone;

    @Column(name = "package_description", nullable = false)
    private String packageDescription;

    @Enumerated(EnumType.STRING)
    @Column(name = "package_size", nullable = false)
    private PackageSize packageSize;

    @Column(name = "package_weight_grams", nullable = false)
    private int packageWeightGrams;

    private String instructions;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** @param maxWeightGrams heaviest package accepted (TBD-08) */
    public static DeliveryDetails of(UUID tripId, String recipientName, String recipientPhone,
                                     String packageDescription, PackageSize packageSize, int packageWeightGrams,
                                     String instructions, int maxWeightGrams, Instant now) {
        if (packageWeightGrams <= 0 || packageWeightGrams > maxWeightGrams) {
            throw DomainException.rule("The package must weigh between 1 g and " + maxWeightGrams + " g");
        }
        DeliveryDetails details = new DeliveryDetails();
        details.tripId = Objects.requireNonNull(tripId);
        details.recipientName = Objects.requireNonNull(recipientName).strip();
        details.recipientPhone = normalizePhone(recipientPhone);
        details.packageDescription = Objects.requireNonNull(packageDescription).strip();
        details.packageSize = Objects.requireNonNull(packageSize);
        details.packageWeightGrams = packageWeightGrams;
        details.instructions = instructions == null || instructions.isBlank() ? null : instructions.strip();
        details.createdAt = now;
        return details;
    }

    /** Spaces, dots and dashes are dropped; 8 to 15 digits with an optional leading +. */
    static String normalizePhone(String phone) {
        String normalized = phone == null ? "" : phone.replaceAll("[\\s.()-]", "");
        if (!PHONE.matcher(normalized).matches()) {
            throw DomainException.rule("The recipient phone number is not valid");
        }
        return normalized;
    }
}
