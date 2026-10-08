package com.example.txn.processing;

/** A temporary failure (timeout, downstream unavailable). Safe to retry. */
public class TransientProcessingException extends RuntimeException {

    public TransientProcessingException(String message) {
        super(message);
    }
}
