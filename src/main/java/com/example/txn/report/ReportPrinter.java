package com.example.txn.report;

import com.example.txn.model.TransactionRecord;
import com.example.txn.model.TransactionStatus;
import com.example.txn.util.Masking;

import java.util.List;
import java.util.Map;

/** Renders a {@link ProcessingSummary} as a readable text report. Account ids are masked. */
public final class ReportPrinter {

    private ReportPrinter() {}

    public static String format(String title, ProcessingSummary s) {
        StringBuilder sb = new StringBuilder();
        sb.append("==================================================\n");
        sb.append(" ").append(title).append('\n');
        sb.append("==================================================\n");
        sb.append("Total requests received : ").append(s.totalReceived()).append('\n');
        sb.append("\nCounts by status\n");
        for (TransactionStatus status : TransactionStatus.values()) {
            sb.append(String.format("  %-17s %d%n", status, s.count(status)));
        }
        sb.append("\nRetries\n");
        sb.append("  Transactions retried : ").append(s.retriedTransactions()).append('\n');
        sb.append("  Extra attempts       : ").append(s.totalRetryAttempts()).append('\n');

        sb.append("\nMissing sequence numbers (waiting for input)\n");
        if (s.missingSequences().isEmpty()) {
            sb.append("  none\n");
        } else {
            for (Map.Entry<String, List<Long>> e : s.missingSequences().entrySet()) {
                sb.append("  ").append(Masking.maskAccount(e.getKey())).append(" -> ")
                        .append(e.getValue()).append('\n');
            }
        }

        sb.append("\nAccounts blocked by a FAILED transaction\n");
        if (s.blockedAccounts().isEmpty()) {
            sb.append("  none\n");
        } else {
            for (Map.Entry<String, String> e : s.blockedAccounts().entrySet()) {
                sb.append("  ").append(Masking.maskAccount(e.getKey())).append(" -> ")
                        .append(e.getValue()).append('\n');
            }
        }

        sb.append("\nBalances\n");
        s.balances().forEach((account, balance) ->
                sb.append("  ").append(Masking.maskAccount(account)).append(" : ").append(balance).append('\n'));

        sb.append("\nTransaction details\n");
        sb.append(String.format("  %-10s %-9s %-10s %-4s %-17s %-3s %s%n",
                "TXN", "ACCOUNT", "TYPE", "SEQ", "STATUS", "TRY", "REASON"));
        for (TransactionRecord r : s.records()) {
            sb.append(String.format("  %-10s %-9s %-10s %-4s %-17s %-3d %s%n",
                    r.transactionId(),
                    Masking.maskAccount(r.accountId()),
                    r.type() == null ? "-" : r.type(),
                    r.sequenceNumber() == null ? "-" : r.sequenceNumber(),
                    r.status(),
                    r.attempts(),
                    r.reason() == null ? "" : r.reason()));
        }
        return sb.toString();
    }
}
