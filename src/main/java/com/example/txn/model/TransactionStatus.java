package com.example.txn.model;

/**
 * Lifecycle of a transaction.
 *
 * <pre>
 * RECEIVED -> PENDING_SEQUENCE (waiting for an earlier sequence number)
 * RECEIVED / PENDING_SEQUENCE -> PROCESSING -> PROCESSED
 *                                  |-> RETRY_PENDING -> PROCESSING (transient failure, retry)
 *                                  |-> FAILED   (retries exhausted, recoverable by operator)
 *                                  |-> REJECTED (business rule broken, not retryable)
 * DUPLICATE and REJECTED (invalid input) are terminal and have no business effect.
 * </pre>
 */
public enum TransactionStatus {
    RECEIVED,
    PENDING_SEQUENCE,
    PROCESSING,
    RETRY_PENDING,
    PROCESSED,
    DUPLICATE,
    REJECTED,
    FAILED
}
