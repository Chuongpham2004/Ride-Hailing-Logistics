package com.rhl.user.application.driver;

/**
 * Private object store for document files. Keys are built by the service from IDs; objects are
 * never public and are only served through user-service after an access check.
 */
public interface DocumentStorage {

    void put(String key, byte[] content, String contentType);

    /** @throws StorageUnavailableException when the store cannot be reached or the object is gone */
    byte[] get(String key);

    class StorageUnavailableException extends RuntimeException {

        public StorageUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
