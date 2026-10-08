package com.example.txn.ledger;

import com.example.txn.model.TransactionType;

import java.math.BigDecimal;

/**
 * One posted transaction. Keeps the balance before the posting so that a posting can be undone
 * (compensated) exactly.
 */
public record LedgerEntry(
        String transactionId,
        String accountId,
        TransactionType type,
        BigDecimal signedAmount,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        boolean currencyAssigned,
        String reversesTransactionId) {
}
