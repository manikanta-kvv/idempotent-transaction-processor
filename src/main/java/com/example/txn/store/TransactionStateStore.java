package com.example.txn.store;

import com.example.txn.model.TransactionRecord;

import java.util.Collection;
import java.util.Optional;

/**
 * Idempotency / state store. It remembers every unique transaction (by transactionId) and every
 * request id that was accepted. In production this is a table with unique constraints on both
 * columns, so duplicate detection also holds across restarts and across several workers.
 */
public interface TransactionStateStore {

    /**
     * Registers a new unique transaction atomically.
     *
     * @return false when the transactionId or the requestId is already registered.
     */
    boolean registerIfAbsent(TransactionRecord record);

    Optional<TransactionRecord> findByTransactionId(String transactionId);

    Optional<TransactionRecord> findByRequestId(String requestId);

    Collection<TransactionRecord> findAll();
}
