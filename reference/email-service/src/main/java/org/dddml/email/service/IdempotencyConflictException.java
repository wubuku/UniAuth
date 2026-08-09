package org.dddml.email.service;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("Idempotency key was already used for a different request");
    }
}
