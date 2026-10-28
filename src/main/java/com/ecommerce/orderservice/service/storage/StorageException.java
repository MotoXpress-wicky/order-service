package com.ecommerce.orderservice.service.storage;

/** The object storage did not accept the request (it is down, or the keys are wrong). */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
