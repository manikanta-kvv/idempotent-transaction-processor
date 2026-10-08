package com.example.txn.ledger;

import com.example.txn.model.Transaction;
import com.example.txn.processing.BusinessRuleException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The system of record for balances. {@link #post(Transaction)} is idempotent per transactionId and
 * atomic: it either applies the whole effect (balance + ledger entry) or nothing.
 */
public interface AccountLedger {

    enum PostResult {
        /** The effect was applied now. */
        APPLIED,
        /** The same transactionId was already applied earlier, nothing was changed. */
        ALREADY_POSTED
    }

    /** @throws BusinessRuleException for permanent business failures; nothing is changed then. */
    PostResult post(Transaction transaction);

    /**
     * Undoes a posting completely (compensating action). Only allowed for the latest entry of an
     * account, which per-account sequencing guarantees for a failed transaction.
     *
     * @return true if something was undone, false if the transaction was never posted.
     */
    boolean compensate(String transactionId);

    BigDecimal balanceOf(String accountId);

    Map<String, BigDecimal> balances();

    List<LedgerEntry> entriesFor(String accountId);
}
