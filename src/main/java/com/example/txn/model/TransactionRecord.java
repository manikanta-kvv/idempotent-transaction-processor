package com.example.txn.model;

import com.example.txn.util.Masking;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Processing state of one request. This is the "transaction state store" entry: it keeps the
 * current status, the number of attempts, the reason for the last state change and the full
 * status history so that every transaction can be traced.
 */
public class TransactionRecord {

    private final String transactionId;
    private final String requestId;
    private final String accountId;
    private final TransactionType type;
    private final Long sequenceNumber;
    private final Transaction transaction; // null when the input was invalid
    private TransactionStatus status;
    private int attempts;
    private String reason;
    private final List<TransactionStatus> history = new ArrayList<>();

    private TransactionRecord(String transactionId, String requestId, String accountId,
                              TransactionType type, Long sequenceNumber, Transaction transaction,
                              TransactionStatus status, String reason) {
        this.transactionId = transactionId;
        this.requestId = requestId;
        this.accountId = accountId;
        this.type = type;
        this.sequenceNumber = sequenceNumber;
        this.transaction = transaction;
        this.status = status;
        this.reason = reason;
        this.history.add(status);
    }

    /** New unique transaction, status RECEIVED. */
    public static TransactionRecord received(Transaction tx) {
        return new TransactionRecord(tx.transactionId(), tx.requestId(), tx.accountId(), tx.type(),
                tx.sequenceNumber(), tx, TransactionStatus.RECEIVED, null);
    }

    /** A repeated request. It has no business effect and never touches the original record. */
    public static TransactionRecord duplicate(Transaction tx, String reason) {
        return new TransactionRecord(tx.transactionId(), tx.requestId(), tx.accountId(), tx.type(),
                tx.sequenceNumber(), tx, TransactionStatus.DUPLICATE, reason);
    }

    /** A valid request that cannot be accepted (for example a sequence conflict). */
    public static TransactionRecord rejected(Transaction tx, String reason) {
        return new TransactionRecord(tx.transactionId(), tx.requestId(), tx.accountId(), tx.type(),
                tx.sequenceNumber(), tx, TransactionStatus.REJECTED, reason);
    }

    /** Malformed input. Only sanitised identifiers are kept, never the full payload. */
    public static TransactionRecord invalid(TransactionRequest raw, String reason) {
        return new TransactionRecord(Masking.safeId(raw.transactionId()), Masking.safeId(raw.requestId()),
                Masking.safeId(raw.accountId()), null, null, null, TransactionStatus.REJECTED, reason);
    }

    public void setStatus(TransactionStatus newStatus, String newReason) {
        if (history.get(history.size() - 1) != newStatus) {
            history.add(newStatus);
        }
        this.status = newStatus;
        this.reason = newReason;
    }

    public void incrementAttempts() {
        attempts++;
    }

    public String transactionId() { return transactionId; }
    public String requestId() { return requestId; }
    public String accountId() { return accountId; }
    public TransactionType type() { return type; }
    public Long sequenceNumber() { return sequenceNumber; }
    public Transaction transaction() { return transaction; }
    public TransactionStatus status() { return status; }
    public int attempts() { return attempts; }
    public String reason() { return reason; }
    public List<TransactionStatus> history() { return Collections.unmodifiableList(history); }
}
