package com.example.txn.downstream;

import com.example.txn.model.Transaction;
import com.example.txn.processing.TransientProcessingException;

/**
 * Output port for the side effect that follows a posted transaction (notification, event on a
 * topic, call to another system). Implementations must treat transactionId as an idempotency key.
 */
public interface DownstreamPublisher {

    /** @throws TransientProcessingException when the call fails temporarily and may be retried. */
    void publish(Transaction transaction);
}
