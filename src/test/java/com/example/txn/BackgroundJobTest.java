package com.example.txn;

import com.example.txn.downstream.SimulatedDownstreamPublisher;
import com.example.txn.ingest.CsvTransactionParser;
import com.example.txn.ingest.InMemoryTransactionSource;
import com.example.txn.job.BackgroundProcessingJob;
import com.example.txn.ledger.InMemoryAccountLedger;
import com.example.txn.model.TransactionRequest;
import com.example.txn.model.TransactionStatus;
import com.example.txn.processing.RetryPolicy;
import com.example.txn.processing.TransactionProcessor;
import com.example.txn.report.ProcessingSummary;
import com.example.txn.report.ReportPrinter;
import com.example.txn.store.InMemoryTransactionStateStore;
import com.example.txn.validation.TransactionValidator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.example.txn.Harness.credit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundJobTest {

    @Test
    void outOfOrderAndDuplicateRequestsAreHandledAcrossBatchBoundaries() {
        Harness h = new Harness();
        List<TransactionRequest> input = List.of(
                credit("T3", "ACC-1", "30.00", 3),
                credit("T2", "ACC-1", "20.00", 2),
                credit("T1", "ACC-1", "10.00", 1),
                credit("T2", "ACC-1", "20.00", 2),   // duplicate in a later batch
                credit("T4", "ACC-1", "40.00", 4));
        BackgroundProcessingJob job = new BackgroundProcessingJob(new InMemoryTransactionSource(input),
                h.processor, 2);

        job.runUntilDrained();

        ProcessingSummary summary = h.processor.summary();
        assertEquals(4, summary.count(TransactionStatus.PROCESSED));
        assertEquals(1, summary.count(TransactionStatus.DUPLICATE));
        assertEquals(new BigDecimal("100.00"), h.ledger.balanceOf("ACC-1"));
    }

    @Test
    void anInvalidRecordInTheMiddleOfABatchDoesNotStopTheBatch() {
        Harness h = new Harness();
        List<TransactionRequest> input = List.of(
                credit("T1", "ACC-1", "10.00", 1),
                new TransactionRequest(null, null, null, null, null, null, null, null, null),
                credit("T2", "ACC-1", "20.00", 2));
        new BackgroundProcessingJob(new InMemoryTransactionSource(input), h.processor, 10).runUntilDrained();

        assertEquals(new BigDecimal("30.00"), h.ledger.balanceOf("ACC-1"));
        assertEquals(1, h.processor.summary().count(TransactionStatus.REJECTED));
    }

    @Test
    void scheduledPollingDrainsTheSourceAndCompletes() throws Exception {
        Harness h = new Harness();
        List<TransactionRequest> input = List.of(credit("T1", "ACC-1", "10.00", 1), credit("T2", "ACC-1", "5.00", 2));
        BackgroundProcessingJob job = new BackgroundProcessingJob(new InMemoryTransactionSource(input), h.processor, 1);

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            job.startPolling(executor, 10).get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(new BigDecimal("15.00"), h.ledger.balanceOf("ACC-1"));
    }

    @Test
    void bundledSampleFileGivesTheDocumentedResult() {
        SimulatedDownstreamPublisher publisher = new SimulatedDownstreamPublisher(Map.of("TXN-2002", 2, "TXN-3001", 4));
        InMemoryAccountLedger ledger = new InMemoryAccountLedger();
        TransactionProcessor processor = new TransactionProcessor(new TransactionValidator(),
                new InMemoryTransactionStateStore(), ledger, publisher, new RetryPolicy(3, 1, 2.0), ms -> { });
        List<TransactionRequest> requests = CsvTransactionParser.parseClasspath("/sample-transactions.csv");
        new BackgroundProcessingJob(new InMemoryTransactionSource(requests), processor, 4).runUntilDrained();

        ProcessingSummary first = processor.summary();
        assertEquals(18, first.totalReceived());
        assertEquals(8, first.count(TransactionStatus.PROCESSED));
        assertEquals(2, first.count(TransactionStatus.PENDING_SEQUENCE));
        assertEquals(2, first.count(TransactionStatus.DUPLICATE));
        assertEquals(5, first.count(TransactionStatus.REJECTED));
        assertEquals(1, first.count(TransactionStatus.FAILED));
        assertEquals(new BigDecimal("1500.00"), ledger.balanceOf("ACC-1001"));
        assertEquals(new BigDecimal("0.00"), ledger.balanceOf("ACC-3003"));
        assertEquals(List.of(2L), first.missingSequences().get("ACC-6006"));

        assertEquals(1, processor.retryFailed());
        ProcessingSummary second = processor.summary();
        assertEquals(10, second.count(TransactionStatus.PROCESSED));
        assertEquals(0, second.count(TransactionStatus.FAILED));
        assertEquals(new BigDecimal("250.00"), ledger.balanceOf("ACC-3003"));
    }

    @Test
    void reportContainsMaskedAccountsOnly() {
        Harness h = new Harness();
        h.send(credit("T1", "ACC-1001", "10.00", 1));
        String report = ReportPrinter.format("TEST", h.processor.summary());

        assertTrue(report.contains("****1001"));
        assertFalse(report.contains("ACC-1001"));
    }
}
