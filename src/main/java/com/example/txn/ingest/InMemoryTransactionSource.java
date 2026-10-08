package com.example.txn.ingest;

import com.example.txn.model.TransactionRequest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Simple source backed by a list. Used by the demo, the CSV adapter and the tests. */
public class InMemoryTransactionSource implements TransactionSource {

    private final Deque<TransactionRequest> queue;

    public InMemoryTransactionSource(List<TransactionRequest> requests) {
        this.queue = new ArrayDeque<>(requests);
    }

    @Override
    public synchronized List<TransactionRequest> nextBatch(int maxSize) {
        List<TransactionRequest> batch = new ArrayList<>();
        while (batch.size() < maxSize && !queue.isEmpty()) {
            batch.add(queue.poll());
        }
        return batch;
    }
}
