package com.rhl.user.domain.driver;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An uploaded scan or photo of a driver document (FR-DRV). The bytes are in the private object
 * store under {@link #objectKey}, which is built from IDs only, never from a client file name.
 * A file is uploaded first and then attached to exactly one document of the same driver.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "document_files")
public class DocumentFile {

    public enum Status {
        UPLOADED,
        ATTACHED
    }

    @Id
    private UUID id;

    @Column(name = "driver_id", nullable = false)
    private UUID driverId;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 64)
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "attached_at")
    private Instant attachedAt;

    public static DocumentFile uploaded(UUID id, UUID driverId, String contentType, long sizeBytes, String sha256,
                                        Instant now) {
        DocumentFile file = new DocumentFile();
        file.id = Objects.requireNonNull(id);
        file.driverId = Objects.requireNonNull(driverId);
        file.objectKey = objectKey(driverId, id);
        file.contentType = Objects.requireNonNull(contentType);
        file.sizeBytes = sizeBytes;
        file.sha256 = Objects.requireNonNull(sha256);
        file.status = Status.UPLOADED;
        file.createdAt = now;
        return file;
    }

    public static String objectKey(UUID driverId, UUID fileId) {
        return "drivers/" + driverId + "/" + fileId;
    }

    /** Backs a document of its own driver; a file is never shared between documents. */
    public void attach(UUID documentDriverId, Instant now) {
        if (!driverId.equals(documentDriverId)) {
            throw DomainException.rule("The file belongs to another driver");
        }
        if (status != Status.UPLOADED) {
            throw DomainException.invalidState("The file is already attached to a document");
        }
        status = Status.ATTACHED;
        attachedAt = now;
    }

    /** File extension matching the stored content type, for download names. */
    public String extension() {
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            default -> "pdf";
        };
    }
}
