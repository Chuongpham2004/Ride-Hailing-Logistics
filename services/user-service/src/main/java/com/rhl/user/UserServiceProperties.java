package com.rhl.user;

import com.rhl.user.domain.driver.DocumentType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Set;

/** Business and security parameters (NFR-MNT-005: nothing here is hard-coded). */
@ConfigurationProperties("rhl")
public record UserServiceProperties(Jwt jwt, Login login, Driver driver, Bootstrap bootstrap, Kafka kafka) {

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

    public record Driver(Set<DocumentType> requiredDocuments, Set<DocumentType> requiredVehicleDocuments) {
    }

    /** Creates the first administrator on an empty database. Leave the email empty to skip. */
    public record Bootstrap(String adminEmail, String adminPassword) {
    }

    public record Kafka(int partitions, short replicas) {
    }
}
