package com.example.txn.downstream;

import com.example.txn.model.Transaction;
import com.example.txn.processing.TransientProcessingException;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Fake downstream system used to simulate transient failures. For each configured transactionId it
 * fails the given number of times and then succeeds. It also acts as an idempotent consumer:
 * delivering the same transactionId twice is recorded once.
 */
public class SimulatedDownstreamPublisher implements DownstreamPublisher {

    private final Map<String, Integer> failuresLeft;
    private final Set<String> delivered = new LinkedHashSet<>();
    private int callCount;
    private int failedCallCount;

    public SimulatedDownstreamPublisher(Map<String, Integer> failuresByTransactionId) {
        this.failuresLeft = new HashMap<>(failuresByTransactionId);
    }

    public static SimulatedDownstreamPublisher alwaysSucceeds() {
        return new SimulatedDownstreamPublisher(Map.of());
    }

    @Override
    public synchronized void publish(Transaction transaction) {
        callCount++;
        int left = failuresLeft.getOrDefault(transaction.transactionId(), 0);
        if (left > 0) {
            failuresLeft.put(transaction.transactionId(), left - 1);
            failedCallCount++;
            throw new TransientProcessingException("Downstream temporarily unavailable");
        }
        delivered.add(transaction.transactionId());
    }

    public synchronized Set<String> deliveredTransactionIds() {
        return new LinkedHashSet<>(delivered);
    }

    public synchronized int callCount() {
        return callCount;
    }

    public synchronized int failedCallCount() {
        return failedCallCount;
    }
}
