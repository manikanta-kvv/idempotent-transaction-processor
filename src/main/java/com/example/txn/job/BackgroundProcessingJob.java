package com.example.txn.job;

import com.example.txn.ingest.TransactionSource;
import com.example.txn.model.TransactionRequest;
import com.example.txn.processing.TransactionProcessor;
import com.example.txn.util.StructuredLog;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * The background job: repeatedly pulls a batch from the {@link TransactionSource} and hands each
 * request to the {@link TransactionProcessor}. A failure while handling one request is contained so
 * that it can never stop the rest of the batch. State (dedup store, sequence buffers) lives in the
 * processor, so duplicates and out-of-order requests are handled correctly across batches too.
 */
public class BackgroundProcessingJob {

    private static final Logger LOG = Logger.getLogger(BackgroundProcessingJob.class.getName());

    private final TransactionSource source;
    private final TransactionProcessor processor;
    private final int batchSize;

    public BackgroundProcessingJob(TransactionSource source, TransactionProcessor processor, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        this.source = source;
        this.processor = processor;
        this.batchSize = batchSize;
    }

    /** Pulls and processes one batch. Returns the number of requests handled (0 = source drained). */
    public int runOnce() {
        List<TransactionRequest> batch = source.nextBatch(batchSize);
        for (TransactionRequest request : batch) {
            try {
                processor.process(request);
            } catch (RuntimeException e) {
                // Last line of defence: log the class only (message may contain customer data) and go on.
                StructuredLog.warn(LOG, "UNHANDLED_ERROR", "type", e.getClass().getSimpleName());
            }
        }
        if (!batch.isEmpty()) {
            StructuredLog.info(LOG, "BATCH_DONE", "size", batch.size());
        }
        return batch.size();
    }

    /** Synchronous variant: process until the source is empty. */
    public void runUntilDrained() {
        while (runOnce() > 0) {
            // keep polling
        }
    }

    /**
     * Scheduled variant: polls on the given executor until the source is empty. The returned future
     * completes when the source is drained.
     */
    public CompletableFuture<Void> startPolling(ScheduledExecutorService executor, long delayMillis) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        ScheduledFuture<?> task = executor.scheduleWithFixedDelay(() -> {
            try {
                if (runOnce() == 0) {
                    done.complete(null);
                }
            } catch (RuntimeException e) {
                done.completeExceptionally(e);
            }
        }, 0, delayMillis, TimeUnit.MILLISECONDS);
        done.whenComplete((result, error) -> task.cancel(false));
        return done;
    }
}
