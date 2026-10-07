package com.rhl.user.infrastructure.storage;

import com.rhl.user.UserServiceProperties;
import com.rhl.user.application.driver.DocumentStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.net.URI;
import java.time.Duration;

/**
 * Document files in an S3-compatible store: MinIO in development, any S3 service elsewhere by
 * configuration only. The bucket is private; objects are written with server-side encryption
 * where the store supports it.
 */
@Slf4j
@Component
public class S3DocumentStorage implements DocumentStorage, AutoCloseable {

    private final S3Client s3;
    private final UserServiceProperties.Storage config;

    public S3DocumentStorage(UserServiceProperties properties) {
        this.config = properties.storage();
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(config.endpoint()))
                .region(Region.of(config.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(config.accessKey(), config.secretKey())))
                .forcePathStyle(config.pathStyle())
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(3))
                        .socketTimeout(Duration.ofSeconds(20)))
                .build();
        if (config.createBucket()) {
            createBucket();
        }
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        try {
            s3.putObject(b -> {
                b.bucket(config.bucket()).key(key).contentType(contentType);
                if (config.serverSideEncryption()) {
                    b.serverSideEncryption(ServerSideEncryption.AES256);
                }
            }, RequestBody.fromBytes(content));
        } catch (SdkException e) {
            throw new StorageUnavailableException("Could not store the file", e);
        }
    }

    @Override
    public byte[] get(String key) {
        try {
            return s3.getObjectAsBytes(b -> b.bucket(config.bucket()).key(key)).asByteArray();
        } catch (SdkException e) {
            throw new StorageUnavailableException("Could not read the file", e);
        }
    }

    /** Development convenience; in other environments the bucket is provisioned with its policies. */
    private void createBucket() {
        try {
            s3.headBucket(b -> b.bucket(config.bucket()));
        } catch (NoSuchBucketException e) {
            try {
                s3.createBucket(b -> b.bucket(config.bucket()));
                log.info("Created document bucket {}", config.bucket());
            } catch (BucketAlreadyOwnedByYouException ignored) {
                // Another instance created it first.
            }
        } catch (SdkException e) {
            // Start anyway: uploads answer 503 until the store is reachable.
            log.warn("Document store not reachable at start-up: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        s3.close();
    }
}
