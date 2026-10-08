package com.example.txn.report;

import com.example.txn.model.TransactionRecord;
import com.example.txn.model.TransactionStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Snapshot of what the service has done so far.
 *
 * @param totalReceived     every request seen, including duplicates and invalid ones
 * @param countsByStatus    number of requests per status (all statuses present, zero included)
 * @param retriedTransactions number of transactions that needed more than one attempt
 * @param totalRetryAttempts  extra attempts beyond the first, summed over all transactions
 * @param missingSequences  per account: sequence numbers never received but needed to continue
 * @param blockedAccounts   per account: description of the FAILED transaction that blocks it
 * @param balances          current balance per account
 * @param records           every request with its final status, in processing-store order
 */
public record ProcessingSummary(
        int totalReceived,
        Map<TransactionStatus, Long> countsByStatus,
        int retriedTransactions,
        int totalRetryAttempts,
        Map<String, List<Long>> missingSequences,
        Map<String, String> blockedAccounts,
        Map<String, BigDecimal> balances,
        List<TransactionRecord> records) {

    public long count(TransactionStatus status) {
        return countsByStatus.getOrDefault(status, 0L);
    }
}
