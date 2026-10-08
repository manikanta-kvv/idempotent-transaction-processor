package com.example.txn;

import com.example.txn.downstream.SimulatedDownstreamPublisher;
import com.example.txn.ledger.InMemoryAccountLedger;
import com.example.txn.model.TransactionRecord;
import com.example.txn.model.TransactionRequest;
import com.example.txn.processing.RetryPolicy;
import com.example.txn.processing.TransactionProcessor;
import com.example.txn.store.InMemoryTransactionStateStore;
import com.example.txn.validation.TransactionValidator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Wires a processor with in-memory collaborators. Back-off waits are recorded, not slept. */
final class Harness {

    final InMemoryAccountLedger ledger = new InMemoryAccountLedger();
    final SimulatedDownstreamPublisher publisher;
    final List<Long> sleeps = new ArrayList<>();
    final TransactionProcessor processor;

    Harness() {
        this(Map.of());
    }

    Harness(Map<String, Integer> downstreamFailures) {
        this.publisher = new SimulatedDownstreamPublisher(downstreamFailures);
        this.processor = new TransactionProcessor(new TransactionValidator(),
                new InMemoryTransactionStateStore(), ledger, publisher,
                new RetryPolicy(3, 100, 2.0), sleeps::add);
    }

    TransactionRecord send(TransactionRequest request) {
        return processor.process(request);
    }

    static TransactionRequest credit(String txn, String account, String amount, long seq) {
        return TransactionRequest.of(txn, "REQ-" + txn, account, "CREDIT", amount, "INR", seq);
    }

    static TransactionRequest debit(String txn, String account, String amount, long seq) {
        return TransactionRequest.of(txn, "REQ-" + txn, account, "DEBIT", amount, "INR", seq);
    }
}
