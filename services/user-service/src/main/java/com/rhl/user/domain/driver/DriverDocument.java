package com.rhl.user.domain.driver;

import com.rhl.common.id.UuidV7;
import com.rhl.user.domain.DomainException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Identity, licence and vehicle papers. Sensitive data (README §10.3): only the owner and
 * reviewers/administrators may read it. Re-uploading a type replaces the current one so the
 * history stays available for audits.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "driver_documents")
public class DriverDocument {

    public enum Status { ACTIVE, REPLACED }

    @Id
    private UUID id;

    @Column(name = "driver_id", nullable = false)
    private UUID driverId;

    @Column(name = "vehicle_id")
    private UUID vehicleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentType type;

    @Column(name = "document_number", nullable = false)
    private String documentNumber;

    @Column(name = "issued_on")
    private LocalDate issuedOn;

    @Column(name = "expires_on")
    private LocalDate expiresOn;

    /** Reference to the file in protected storage, never a public URL. */
    /** Documents submitted before uploads existed; new documents use {@link #fileId}. */
    @Column(name = "file_ref")
    private String fileRef;

    @Column(name = "file_id")
    private UUID fileId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static DriverDocument submit(UUID driverId, UUID vehicleId, DocumentType type, String documentNumber,
                                        LocalDate issuedOn, LocalDate expiresOn, UUID fileId, Instant now) {
        if (type.isVehicleScoped() != (vehicleId != null)) {
            throw DomainException.rule(type.isVehicleScoped()
                    ? type + " must reference a vehicle"
                    : type + " cannot reference a vehicle");
        }
        if (issuedOn != null && expiresOn != null && !expiresOn.isAfter(issuedOn)) {
            throw DomainException.rule("Expiry date must be after issue date");
        }
        DriverDocument document = new DriverDocument();
        document.id = UuidV7.random();
        document.driverId = driverId;
        document.vehicleId = vehicleId;
        document.type = type;
        document.documentNumber = documentNumber.strip();
        document.issuedOn = issuedOn;
        document.expiresOn = expiresOn;
        document.fileId = fileId;
        document.status = Status.ACTIVE;
        document.createdAt = now;
        return document;
    }

    /** Valid on {@code date}: documents without expiry date never expire. */
    public boolean isValidOn(LocalDate date) {
        return status == Status.ACTIVE && (expiresOn == null || expiresOn.isAfter(date));
    }

    public void replace() {
        status = Status.REPLACED;
    }
}
