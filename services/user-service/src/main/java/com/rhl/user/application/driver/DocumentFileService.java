package com.rhl.user.application.driver;

import com.rhl.common.id.UuidV7;
import com.rhl.common.web.ApiException;
import com.rhl.common.web.ErrorCode;
import com.rhl.user.UserServiceProperties;
import com.rhl.user.application.AuditLog;
import com.rhl.user.domain.driver.DocumentFile;
import com.rhl.user.domain.driver.DriverDocument;
import com.rhl.user.domain.driver.UploadInspector;
import com.rhl.user.infrastructure.cache.ClientRateLimiter;
import com.rhl.user.infrastructure.persistence.DocumentFileRepository;
import com.rhl.user.infrastructure.persistence.DriverDocumentRepository;
import com.rhl.user.infrastructure.persistence.DriverProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Upload and download of document files (FR-DRV, README §9). Files are checked by
 * {@link UploadInspector}, stored privately and served only to their driver or to reviewers;
 * a reviewer opening a file is audited, since these are identity documents (BR-014).
 */
@Service
public class DocumentFileService {

    private final DocumentFileRepository files;
    private final DriverDocumentRepository documents;
    private final DriverProfileRepository profiles;
    private final DocumentStorage storage;
    private final ClientRateLimiter rateLimiter;
    private final AuditLog audit;
    private final TransactionTemplate tx;
    private final UploadInspector inspector;
    private final UserServiceProperties.Documents config;
    private final Clock clock;

    public DocumentFileService(DocumentFileRepository files, DriverDocumentRepository documents,
                               DriverProfileRepository profiles, DocumentStorage storage,
                               ClientRateLimiter rateLimiter, AuditLog audit, TransactionTemplate tx,
                               UserServiceProperties properties, Clock clock) {
        this.files = files;
        this.documents = documents;
        this.profiles = profiles;
        this.storage = storage;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.tx = tx;
        this.config = properties.documents();
        this.inspector = new UploadInspector(config.maxFileSize().toBytes(), config.maxPixels());
        this.clock = clock;
    }

    /** @param fileId pass as {@code fileId} when submitting the document */
    public record Uploaded(UUID fileId, String contentType, long sizeBytes, String sha256) {
    }

    public record Download(byte[] content, String contentType, String fileName) {
    }

    /**
     * Checks and stores a file for a later document of this driver. The bytes go to the store
     * before the row is written: a failure in between leaves an unreferenced object, never a row
     * pointing at nothing.
     */
    public Uploaded upload(UUID driverId, String fileName, byte[] content) {
        if (!profiles.existsById(driverId)) {
            throw ApiException.notFound("Driver profile");
        }
        if (!rateLimiter.tryAcquire("document-upload", driverId.toString(), config.uploadsPerHour(),
                Duration.ofHours(1))) {
            throw new ApiException(ErrorCode.RATE_LIMIT_EXCEEDED, "Too many uploads, try again later");
        }
        UploadInspector.Accepted accepted = inspector.inspect(fileName, content);
        DocumentFile file = DocumentFile.uploaded(UuidV7.random(), driverId, accepted.contentType(),
                accepted.content().length, sha256(accepted.content()), clock.instant());
        try {
            storage.put(file.getObjectKey(), accepted.content(), accepted.contentType());
        } catch (DocumentStorage.StorageUnavailableException e) {
            throw unavailable();
        }
        tx.executeWithoutResult(status -> {
            files.save(file);
            audit.success(driverId, "DOCUMENT_FILE_UPLOADED", "DOCUMENT_FILE", file.getId(),
                    Map.of("contentType", file.getContentType(), "sizeBytes", file.getSizeBytes()));
        });
        return new Uploaded(file.getId(), file.getContentType(), file.getSizeBytes(), file.getSha256());
    }

    /** The driver's own file; anyone else's document is not found. */
    @Transactional(readOnly = true)
    public Download forDriver(UUID driverId, UUID documentId) {
        return read(fileOf(driverId, documentId), documentId);
    }

    /** A reviewer opens a driver's identity document: allowed, and recorded (BR-014). */
    @Transactional
    public Download forReviewer(UUID reviewerId, UUID driverId, UUID documentId) {
        DocumentFile file = fileOf(driverId, documentId);
        audit.success(reviewerId, "DOCUMENT_FILE_VIEWED", "DRIVER_DOCUMENT", documentId,
                Map.of("driverId", driverId.toString(), "fileId", file.getId().toString()));
        return read(file, documentId);
    }

    private DocumentFile fileOf(UUID driverId, UUID documentId) {
        DriverDocument document = documents.findById(documentId)
                .filter(d -> d.getDriverId().equals(driverId))
                .orElseThrow(() -> ApiException.notFound("Document"));
        if (document.getFileId() == null) {
            throw ApiException.notFound("File");
        }
        return files.findById(document.getFileId()).orElseThrow(() -> ApiException.notFound("File"));
    }

    private Download read(DocumentFile file, UUID documentId) {
        try {
            return new Download(storage.get(file.getObjectKey()), file.getContentType(),
                    "document-" + documentId + "." + file.extension());
        } catch (DocumentStorage.StorageUnavailableException e) {
            throw unavailable();
        }
    }

    private static ApiException unavailable() {
        return new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "File storage is unavailable, try again shortly");
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
