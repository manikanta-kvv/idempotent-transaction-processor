package com.example.txn.store;

import com.example.txn.model.TransactionRecord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** In-memory implementation. Thread-safe through method-level locking. */
public class InMemoryTransactionStateStore implements TransactionStateStore {

    private final Map<String, TransactionRecord> byTransactionId = new LinkedHashMap<>();
    private final Map<String, TransactionRecord> byRequestId = new LinkedHashMap<>();

    @Override
    public synchronized boolean registerIfAbsent(TransactionRecord record) {
        if (byTransactionId.containsKey(record.transactionId())
                || byRequestId.containsKey(record.requestId())) {
            return false;
        }
        byTransactionId.put(record.transactionId(), record);
        byRequestId.put(record.requestId(), record);
        return true;
    }

    @Override
    public synchronized Optional<TransactionRecord> findByTransactionId(String transactionId) {
        return Optional.ofNullable(byTransactionId.get(transactionId));
    }

    @Override
    public synchronized Optional<TransactionRecord> findByRequestId(String requestId) {
        return Optional.ofNullable(byRequestId.get(requestId));
    }

    @Override
    public synchronized Collection<TransactionRecord> findAll() {
        return new ArrayList<>(byTransactionId.values());
    }
}
