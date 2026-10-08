package com.example.txn.model;

/**
 * Raw request exactly as received from the input source. Every field is a String so that
 * malformed input (bad number, missing value) can be captured and rejected by the validator
 * instead of crashing the input adapter or the whole batch.
 */
public record TransactionRequest(
        String transactionId,
        String requestId,
        String accountId,
        String transactionType,
        String amount,
        String currency,
        String sequenceNumber,
        String eventTime,
        String originalTransactionId) {

    /** Convenience factory used by tests and in-memory sources. */
    public static TransactionRequest of(String transactionId, String requestId, String accountId,
                                        String type, String amount, String currency, long sequence) {
        return new TransactionRequest(transactionId, requestId, accountId, type, amount, currency,
                Long.toString(sequence), null, null);
    }

    public static TransactionRequest reversal(String transactionId, String requestId, String accountId,
                                              String amount, String currency, long sequence,
                                              String originalTransactionId) {
        return new TransactionRequest(transactionId, requestId, accountId, "REVERSAL", amount, currency,
                Long.toString(sequence), null, originalTransactionId);
    }
}
