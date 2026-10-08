package com.example.txn.ingest;

import com.example.txn.model.TransactionRequest;

import java.util.List;

/**
 * Input port. Anything that can hand over batches of raw requests can feed the service: a list,
 * a CSV file, a database table poller, a Kafka/SQS consumer adapter, an HTTP endpoint, etc.
 * The business logic never depends on the concrete source.
 */
public interface TransactionSource {

    /** Returns up to {@code maxSize} requests, or an empty list when the source is drained. */
    List<TransactionRequest> nextBatch(int maxSize);
}
