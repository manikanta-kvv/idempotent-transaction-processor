package com.example.txn;

import com.example.txn.downstream.SimulatedDownstreamPublisher;
import com.example.txn.ingest.CsvTransactionParser;
import com.example.txn.ingest.InMemoryTransactionSource;
import com.example.txn.ingest.TransactionSource;
import com.example.txn.job.BackgroundProcessingJob;
import com.example.txn.ledger.InMemoryAccountLedger;
import com.example.txn.processing.RetryPolicy;
import com.example.txn.processing.Sleeper;
import com.example.txn.processing.TransactionProcessor;
import com.example.txn.report.ReportPrinter;
import com.example.txn.store.InMemoryTransactionStateStore;
import com.example.txn.validation.TransactionValidator;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Demo runner. Usage: {@code mvn -q compile exec:java [-Dexec.args="path/to/input.csv"]}.
 * Without an argument it processes the bundled sample-transactions.csv.
 *
 * <p>Simulated failures (only meaningful for the bundled sample data):
 * TXN-2002 fails twice then succeeds (retry works), TXN-3001 fails 4 times (retries exhausted ->
 * FAILED), and succeeds during the operator recovery run.
 */
public final class App {

    private App() {}

    public static void main(String[] args) throws Exception {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tT %4$-7s %5$s%n");

        var requests = args.length > 0
                ? CsvTransactionParser.parse(Path.of(args[0]))
                : CsvTransactionParser.parseClasspath("/sample-transactions.csv");
        TransactionSource source = new InMemoryTransactionSource(requests);

        var publisher = new SimulatedDownstreamPublisher(Map.of("TXN-2002", 2, "TXN-3001", 4));
        var ledger = new InMemoryAccountLedger();
        var processor = new TransactionProcessor(new TransactionValidator(),
                new InMemoryTransactionStateStore(), ledger, publisher,
                new RetryPolicy(3, 50, 2.0), Sleeper.real());

        BackgroundProcessingJob job = new BackgroundProcessingJob(source, processor, 4);
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            job.startPolling(executor, 100).get(60, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        System.out.println();
        System.out.println(ReportPrinter.format("PROCESSING REPORT (after background run)", processor.summary()));

        System.out.println("Operator recovery: retrying FAILED transactions ...");
        int recovered = processor.retryFailed();
        System.out.println("Recovered transactions: " + recovered);
        System.out.println();
        System.out.println(ReportPrinter.format("PROCESSING REPORT (after recovery)", processor.summary()));
    }
}
