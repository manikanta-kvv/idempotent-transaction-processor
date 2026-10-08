package com.example.txn.model;

/** Kind of business transaction. A REVERSAL undoes the effect of an earlier transaction. */
public enum TransactionType {
    CREDIT,
    DEBIT,
    REVERSAL
}
