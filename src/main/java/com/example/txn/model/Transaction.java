package com.example.txn.model;

import java.math.BigDecimal;
import java.time.Instant;

/** A validated, typed transaction. Only valid data ever reaches the business logic. */
public record Transaction(
        String transactionId,
        String requestId,
        String accountId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        long sequenceNumber,
        Instant eventTime,
        String originalTransactionId) {

    /** True when both requests describe the same business transaction (requestId may differ). */
    public boolean samePayloadAs(Transaction other) {
        return accountId.equals(other.accountId)
                && type == other.type
                && amount.compareTo(other.amount) == 0
                && currency.equals(other.currency)
                && sequenceNumber == other.sequenceNumber
                && java.util.Objects.equals(originalTransactionId, other.originalTransactionId);
    }
}
