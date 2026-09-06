package com.wallet.system.exception;

/**
 * Thrown when an optimistic-lock retry budget is exhausted, or surfaced
 * to the client instead of a raw OptimisticLockException / PessimisticLockException.
 */
public class ConcurrencyConflictException extends RuntimeException {
    public ConcurrencyConflictException(String message) {
        super(message);
    }
}
