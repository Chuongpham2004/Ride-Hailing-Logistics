package com.rhl.user.application.driver;

import com.rhl.user.UserServiceProperties;
import com.rhl.user.domain.driver.DocumentFile;
import com.rhl.user.infrastructure.persistence.DocumentFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Deletes uploaded files that were never attached to a document within
 * {@code rhl.documents.orphan-retention} (README §9: the store keeps only what a document needs).
 * Each file is handled in its own transaction: the row is locked, the object deleted, then the
 * row. If the store fails, the row stays and the file is tried again on the next run; a file a
 * driver is attaching at that moment is skipped.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrphanFileSweeper {

    private static final int BATCH = 100;

    private final DocumentFileRepository files;
    private final DocumentStorage storage;
    private final TransactionTemplate transactions;
    private final UserServiceProperties properties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${rhl.documents.orphan-check-interval}",
            initialDelayString = "${rhl.documents.orphan-check-interval}")
    public void sweep() {
        try {
            int deleted = purge();
            if (deleted > 0) {
                log.info("Deleted {} uploaded files never attached to a document", deleted);
            }
        } catch (DataAccessException e) {
            log.warn("Orphan file sweep failed: {}", e.getMostSpecificCause().getMessage());
        }
    }

    /** @return files deleted in this run */
    public int purge() {
        Instant cutoff = clock.instant().minus(properties.documents().orphanRetention());
        int deleted = 0;
        for (UUID id : files.findOrphanIds(cutoff, BATCH)) {
            try {
                if (Boolean.TRUE.equals(transactions.execute(status -> delete(id, cutoff)))) {
                    deleted++;
                }
            } catch (DocumentStorage.StorageUnavailableException | DataAccessException e) {
                log.warn("Orphan file {} not deleted this run: {}", id, e.getMessage());
            }
        }
        return deleted;
    }

    private boolean delete(UUID id, Instant cutoff) {
        Optional<DocumentFile> file = files.lockOrphan(id, cutoff);
        if (file.isEmpty()) {
            return false; // attached meanwhile, or locked by an attach
        }
        storage.delete(file.get().getObjectKey());
        files.delete(file.get());
        return true;
    }
}
