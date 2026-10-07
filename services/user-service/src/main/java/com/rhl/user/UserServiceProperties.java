package com.rhl.user;

import com.rhl.user.domain.driver.DocumentType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.Set;

/** Business and security parameters (NFR-MNT-005: nothing here is hard-coded). */
@ConfigurationProperties("rhl")
public record UserServiceProperties(Jwt jwt, Login login, Verification verification, RateLimits rateLimits,
                                    Driver driver, Documents documents, Storage storage, Bootstrap bootstrap,
                                    Kafka kafka) {

    /**
     * @param privateKey PKCS#8 PEM; when both keys are empty an ephemeral key pair is generated (dev only)
     * @param publicKey  X.509 PEM matching {@code privateKey}
     */
    public record Jwt(String issuer, Duration accessTokenTtl, Duration refreshTokenTtl, String keyId,
                      String privateKey, String publicKey) {
    }

    /** FR-IAM-006: after {@code maxFailures} wrong passwords the identifier is blocked for {@code lockDuration}. */
    public record Login(int maxFailures, Duration lockDuration) {
    }

    /**
     * One-time codes for contact verification and password reset (FR-IAM).
     *
     * @param codeTtl         a code is valid this long; a new code replaces the previous one
     * @param maxAttempts     wrong entries before the code is discarded
     * @param resendCooldown  smallest gap between two codes to the same address and purpose
     * @param maxSendsPerHour codes per address and purpose per hour
     */
    public record Verification(Duration codeTtl, int maxAttempts, Duration resendCooldown, int maxSendsPerHour) {
    }

    /**
     * Per client IP, against bulk sign-ups and account probing (NFR-SEC-007). The IP is the
     * client address the gateway forwards; only private-network proxies are trusted to set it.
     */
    public record RateLimits(int registrationsPerHour, int passwordResetsPerHour) {
    }

    /**
     * @param staleOfferGrace         how long past an offer's expiry a driver may still show OFFERED
     *                                before the sweeper frees them (closing event lost or late)
     * @param staleOfferCheckInterval how often the sweeper runs
     */
    public record Driver(Set<DocumentType> requiredDocuments, Set<DocumentType> requiredVehicleDocuments,
                         Duration staleOfferGrace, Duration staleOfferCheckInterval) {
    }

    /**
     * Uploaded document files (README §9).
     *
     * @param maxFileSize    largest file accepted
     * @param maxPixels      largest image (width x height), checked before decoding
     * @param uploadsPerHour uploads per driver per hour
     */
    public record Documents(DataSize maxFileSize, long maxPixels, int uploadsPerHour) {
    }

    /**
     * S3-compatible object store: MinIO in development, any S3 service by configuration.
     *
     * @param createBucket         create the bucket at start-up if missing (development)
     * @param serverSideEncryption request SSE-S3 on every object (needs a store with KMS)
     */
    public record Storage(String endpoint, String region, String bucket, String accessKey, String secretKey,
                          boolean pathStyle, boolean createBucket, boolean serverSideEncryption) {
    }

    /** Creates the first administrator on an empty database. Leave the email empty to skip. */
    public record Bootstrap(String adminEmail, String adminPassword) {
    }

    public record Kafka(int partitions, short replicas) {
    }
}
