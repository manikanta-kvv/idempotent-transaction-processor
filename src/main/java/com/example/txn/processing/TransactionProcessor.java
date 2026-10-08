package com.example.txn.processing;

import com.example.txn.downstream.DownstreamPublisher;
import com.example.txn.ledger.AccountLedger;
import com.example.txn.model.Transaction;
import com.example.txn.model.TransactionRecord;
import com.example.txn.model.TransactionRequest;
import com.example.txn.model.TransactionStatus;
import com.example.txn.report.ProcessingSummary;
import com.example.txn.store.TransactionStateStore;
import com.example.txn.util.Masking;
import com.example.txn.util.StructuredLog;
import com.example.txn.validation.TransactionValidator;
import com.example.txn.validation.ValidationResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * Core business logic. It knows nothing about where requests come from (see TransactionSource).
 *
 * <h3>Rules</h3>
 * <ol>
 *   <li><b>Validate</b>: malformed requests are rejected and isolated; the rest of the batch continues.</li>
 *   <li><b>Deduplicate</b>: a request whose transactionId or requestId was already accepted is marked
 *       DUPLICATE and has no effect, wherever in the stream it arrives.</li>
 *   <li><b>Order (sequence based)</b>: every account has a sequence starting at 1. A request with a
 *       future sequence is buffered as PENDING_SEQUENCE and processed once all earlier sequences are done.
 *       Missing sequences are reported.</li>
 *   <li><b>Process with retry</b>: ledger post (idempotent, atomic) then downstream publish. Transient
 *       failures are retried with back-off up to the limit; then the posting is compensated and the
 *       transaction becomes FAILED, which blocks later sequences of that account until recovered.</li>
 * </ol>
 *
 * <p>Thread-safety: all public methods are synchronized, so one processor can be driven by a background
 * thread while another thread reads the summary.
 */
public class TransactionProcessor {

    private static final Logger LOG = Logger.getLogger(TransactionProcessor.class.getName());

    /** Per-account ordering state. */
    private static final class AccountStream {
        long nextExpected = 1;
        final TreeMap<Long, TransactionRecord> buffer = new TreeMap<>();
    }

    private final TransactionValidator validator;
    private final TransactionStateStore store;
    private final AccountLedger ledger;
    private final DownstreamPublisher publisher;
    private final RetryPolicy retryPolicy;
    private final Sleeper sleeper;

    private final Map<String, AccountStream> streams = new LinkedHashMap<>();
    private final List<TransactionRecord> duplicates = new ArrayList<>();
    private final List<TransactionRecord> rejectedAtIntake = new ArrayList<>();

    public TransactionProcessor(TransactionValidator validator, TransactionStateStore store,
                                AccountLedger ledger, DownstreamPublisher publisher,
                                RetryPolicy retryPolicy, Sleeper sleeper) {
        this.validator = validator;
        this.store = store;
        this.ledger = ledger;
        this.publisher = publisher;
        this.retryPolicy = retryPolicy;
        this.sleeper = sleeper;
    }

    /** Handles one request and returns its record (status reflects the state right after handling). */
    public synchronized TransactionRecord process(TransactionRequest request) {
        // 1. validation
        ValidationResult validation = validator.validate(request);
        if (!validation.isValid()) {
            TransactionRecord invalid = TransactionRecord.invalid(request,
                    "INVALID_INPUT: " + String.join("; ", validation.errors()));
            rejectedAtIntake.add(invalid);
            StructuredLog.warn(LOG, "REJECTED_INVALID", "txnId", invalid.transactionId(),
                    "account", Masking.maskAccount(invalid.accountId()), "errors", validation.errors().size());
            return invalid;
        }
        Transaction tx = validation.transaction();

        // 2. duplicate detection (by business transaction id and by request / idempotency key)
        Optional<TransactionRecord> sameTransaction = store.findByTransactionId(tx.transactionId());
        Optional<TransactionRecord> sameRequest = store.findByRequestId(tx.requestId());
        if (sameTransaction.isPresent() || sameRequest.isPresent()) {
            String reason;
            if (sameTransaction.isPresent()) {
                reason = sameTransaction.get().transaction().samePayloadAs(tx)
                        ? "DUPLICATE_TRANSACTION_ID"
                        : "DUPLICATE_TRANSACTION_ID_DIFFERENT_PAYLOAD";
            } else {
                reason = "DUPLICATE_REQUEST_ID";
            }
            TransactionRecord duplicate = TransactionRecord.duplicate(tx, reason);
            duplicates.add(duplicate);
            if (reason.equals("DUPLICATE_TRANSACTION_ID_DIFFERENT_PAYLOAD")) {
                StructuredLog.warn(LOG, "DUPLICATE_PAYLOAD_MISMATCH", "txnId", tx.transactionId(),
                        "account", Masking.maskAccount(tx.accountId()));
            } else {
                StructuredLog.info(LOG, "DUPLICATE", "txnId", tx.transactionId(), "reason", reason,
                        "account", Masking.maskAccount(tx.accountId()));
            }
            return duplicate;
        }

        // 3. sequence checks
        AccountStream stream = streams.computeIfAbsent(tx.accountId(), k -> new AccountStream());
        if (tx.sequenceNumber() < stream.nextExpected) {
            return rejectAtIntake(tx, "SEQUENCE_ALREADY_CONSUMED");
        }
        if (stream.buffer.containsKey(tx.sequenceNumber())) {
            return rejectAtIntake(tx, "SEQUENCE_CONFLICT");
        }

        // 4. accept: register for idempotency and buffer in sequence order
        TransactionRecord record = TransactionRecord.received(tx);
        if (!store.registerIfAbsent(record)) { // lost a race with another worker: treat as duplicate
            TransactionRecord duplicate = TransactionRecord.duplicate(tx, "DUPLICATE_TRANSACTION_ID");
            duplicates.add(duplicate);
            return duplicate;
        }
        stream.buffer.put(tx.sequenceNumber(), record);
        StructuredLog.info(LOG, "RECEIVED", "txnId", tx.transactionId(), "type", tx.type(),
                "account", Masking.maskAccount(tx.accountId()), "seq", tx.sequenceNumber());

        if (tx.sequenceNumber() != stream.nextExpected) {
            record.setStatus(TransactionStatus.PENDING_SEQUENCE, "WAITING_FOR_SEQUENCE_" + stream.nextExpected);
            StructuredLog.info(LOG, "PENDING_SEQUENCE", "txnId", tx.transactionId(),
                    "waitingFor", stream.nextExpected);
        }

        drain(stream);
        return record;
    }

    /**
     * Operator recovery: retries the FAILED transaction that blocks each account, then continues with the
     * transactions that were waiting behind it. Safe to call repeatedly.
     *
     * @return number of failed transactions that were recovered
     */
    public synchronized int retryFailed() {
        int recovered = 0;
        for (AccountStream stream : streams.values()) {
            var head = stream.buffer.firstEntry();
            if (head != null && head.getValue().status() == TransactionStatus.FAILED) {
                TransactionRecord failed = head.getValue();
                StructuredLog.info(LOG, "RECOVERY_RETRY", "txnId", failed.transactionId());
                failed.setStatus(TransactionStatus.RETRY_PENDING, "OPERATOR_RETRY");
                drain(stream);
                if (failed.status() == TransactionStatus.PROCESSED) {
                    recovered++;
                }
            }
        }
        return recovered;
    }

    // ------------------------------------------------------------------ ordering

    /** Processes buffered transactions of one account in strict sequence order. */
    private void drain(AccountStream stream) {
        while (!stream.buffer.isEmpty()) {
            var head = stream.buffer.firstEntry();
            if (head.getKey() != stream.nextExpected) {
                break; // a sequence number is still missing
            }
            TransactionRecord record = head.getValue();
            if (record.status() == TransactionStatus.FAILED) {
                markBlocked(stream, record); // later arrivals also learn why they are waiting
                break; // blocked until an operator recovers it
            }
            execute(record);
            if (record.status() == TransactionStatus.FAILED) {
                markBlocked(stream, record);
                break;
            }
            // PROCESSED or REJECTED (business): the sequence number is consumed either way
            stream.buffer.pollFirstEntry();
            stream.nextExpected++;
        }
    }

    private void markBlocked(AccountStream stream, TransactionRecord failed) {
        for (TransactionRecord waiting : stream.buffer.values()) {
            if (waiting != failed) {
                waiting.setStatus(TransactionStatus.PENDING_SEQUENCE,
                        "BLOCKED_BY_FAILED_SEQUENCE_" + failed.sequenceNumber());
            }
        }
    }

    // ------------------------------------------------------------------ processing + retry

    private void execute(TransactionRecord record) {
        Transaction tx = record.transaction();
        for (int attempt = 1; attempt <= retryPolicy.maxAttempts(); attempt++) {
            record.incrementAttempts();
            record.setStatus(TransactionStatus.PROCESSING, null);
            try {
                AccountLedger.PostResult posted = ledger.post(tx); // idempotent by transactionId
                publisher.publish(tx);                             // may fail transiently
                record.setStatus(TransactionStatus.PROCESSED,
                        posted == AccountLedger.PostResult.ALREADY_POSTED ? "COMPLETED_AFTER_RETRY" : null);
                StructuredLog.info(LOG, "PROCESSED", "txnId", tx.transactionId(),
                        "account", Masking.maskAccount(tx.accountId()), "attempts", record.attempts());
                return;
            } catch (BusinessRuleException e) {
                record.setStatus(TransactionStatus.REJECTED, e.code());
                StructuredLog.warn(LOG, "REJECTED_BUSINESS", "txnId", tx.transactionId(), "reason", e.code());
                return;
            } catch (TransientProcessingException e) {
                if (attempt < retryPolicy.maxAttempts()) {
                    record.setStatus(TransactionStatus.RETRY_PENDING, "TRANSIENT_FAILURE_ATTEMPT_" + attempt);
                    long wait = retryPolicy.backoffMillis(attempt);
                    StructuredLog.warn(LOG, "RETRY_SCHEDULED", "txnId", tx.transactionId(),
                            "attempt", attempt, "backoffMs", wait);
                    sleeper.sleep(wait);
                } else {
                    fail(record, "RETRIES_EXHAUSTED");
                }
            } catch (RuntimeException e) {
                // Unexpected error: log only the class name (messages may contain customer data).
                fail(record, "UNEXPECTED_ERROR_" + e.getClass().getSimpleName());
                return;
            }
        }
    }

    /** Compensates any partial effect, then marks the transaction FAILED (traceable, recoverable). */
    private void fail(TransactionRecord record, String reason) {
        ledger.compensate(record.transactionId());
        record.setStatus(TransactionStatus.FAILED, reason);
        StructuredLog.warn(LOG, "FAILED", "txnId", record.transactionId(), "reason", reason,
                "attempts", record.attempts());
    }

    private TransactionRecord rejectAtIntake(Transaction tx, String reason) {
        TransactionRecord rejected = TransactionRecord.rejected(tx, reason);
        rejectedAtIntake.add(rejected);
        StructuredLog.warn(LOG, "REJECTED_INTAKE", "txnId", tx.transactionId(), "reason", reason,
                "account", Masking.maskAccount(tx.accountId()));
        return rejected;
    }

    // ------------------------------------------------------------------ reporting

    public synchronized ProcessingSummary summary() {
        List<TransactionRecord> all = new ArrayList<>(store.findAll());
        all.addAll(duplicates);
        all.addAll(rejectedAtIntake);

        Map<TransactionStatus, Long> counts = new EnumMap<>(TransactionStatus.class);
        for (TransactionStatus status : TransactionStatus.values()) {
            counts.put(status, 0L);
        }
        int retried = 0;
        int extraAttempts = 0;
        for (TransactionRecord r : all) {
            counts.merge(r.status(), 1L, Long::sum);
            if (r.attempts() > 1) {
                retried++;
                extraAttempts += r.attempts() - 1;
            }
        }

        Map<String, List<Long>> missing = new LinkedHashMap<>();
        Map<String, String> blocked = new LinkedHashMap<>();
        for (Map.Entry<String, AccountStream> e : streams.entrySet()) {
            AccountStream stream = e.getValue();
            if (stream.buffer.isEmpty()) {
                continue;
            }
            TransactionRecord head = stream.buffer.firstEntry().getValue();
            if (head.status() == TransactionStatus.FAILED) {
                blocked.put(e.getKey(), "sequence " + head.sequenceNumber() + " (" + head.transactionId()
                        + ") FAILED: " + head.reason());
                continue;
            }
            List<Long> gaps = new ArrayList<>();
            for (long seq = stream.nextExpected; seq < stream.buffer.lastKey(); seq++) {
                if (!stream.buffer.containsKey(seq)) {
                    gaps.add(seq);
                }
            }
            if (!gaps.isEmpty()) {
                missing.put(e.getKey(), gaps);
            }
        }

        Map<String, BigDecimal> balances = ledger.balances();
        return new ProcessingSummary(all.size(), counts, retried, extraAttempts, missing, blocked,
                balances, all);
    }
}
