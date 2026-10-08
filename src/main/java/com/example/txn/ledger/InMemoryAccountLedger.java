package com.example.txn.ledger;

import com.example.txn.model.Transaction;
import com.example.txn.model.TransactionType;
import com.example.txn.processing.BusinessRuleException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory ledger. All checks run first and all state changes happen together at the end of the
 * synchronized method, so a rejected or failed posting never leaves partial state behind. With a
 * database this block becomes one local transaction.
 */
public class InMemoryAccountLedger implements AccountLedger {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final Map<String, LedgerEntry> entriesByTransactionId = new HashMap<>();
    private final Map<String, List<LedgerEntry>> entriesByAccount = new LinkedHashMap<>();
    private final Map<String, BigDecimal> balances = new LinkedHashMap<>();
    private final Map<String, String> currencies = new HashMap<>();
    private final Set<String> reversedTransactionIds = new HashSet<>();

    @Override
    public synchronized PostResult post(Transaction tx) {
        if (entriesByTransactionId.containsKey(tx.transactionId())) {
            return PostResult.ALREADY_POSTED; // idempotent: no second effect
        }

        String accountCurrency = currencies.get(tx.accountId());
        if (accountCurrency != null && !accountCurrency.equals(tx.currency())) {
            throw new BusinessRuleException("CURRENCY_MISMATCH");
        }

        BigDecimal before = balances.getOrDefault(tx.accountId(), ZERO);
        BigDecimal delta;
        String reverses = null;

        switch (tx.type()) {
            case CREDIT -> delta = tx.amount();
            case DEBIT -> delta = tx.amount().negate();
            case REVERSAL -> {
                LedgerEntry original = entriesByTransactionId.get(tx.originalTransactionId());
                if (original == null) {
                    throw new BusinessRuleException("ORIGINAL_NOT_POSTED");
                }
                if (!original.accountId().equals(tx.accountId())) {
                    throw new BusinessRuleException("ORIGINAL_ACCOUNT_MISMATCH");
                }
                if (original.type() == TransactionType.REVERSAL) {
                    throw new BusinessRuleException("CANNOT_REVERSE_A_REVERSAL");
                }
                if (reversedTransactionIds.contains(original.transactionId())) {
                    throw new BusinessRuleException("ALREADY_REVERSED");
                }
                if (original.signedAmount().abs().compareTo(tx.amount()) != 0) {
                    throw new BusinessRuleException("REVERSAL_AMOUNT_MISMATCH");
                }
                delta = original.signedAmount().negate();
                reverses = original.transactionId();
            }
            default -> throw new IllegalStateException("Unsupported transaction type");
        }

        BigDecimal after = before.add(delta);
        if (after.signum() < 0) {
            throw new BusinessRuleException("INSUFFICIENT_FUNDS");
        }

        // ---- commit: everything below succeeds together ----
        boolean currencyAssigned = accountCurrency == null;
        if (currencyAssigned) {
            currencies.put(tx.accountId(), tx.currency());
        }
        LedgerEntry entry = new LedgerEntry(tx.transactionId(), tx.accountId(), tx.type(), delta,
                before, after, currencyAssigned, reverses);
        entriesByTransactionId.put(tx.transactionId(), entry);
        entriesByAccount.computeIfAbsent(tx.accountId(), k -> new ArrayList<>()).add(entry);
        balances.put(tx.accountId(), after);
        if (reverses != null) {
            reversedTransactionIds.add(reverses);
        }
        return PostResult.APPLIED;
    }

    @Override
    public synchronized boolean compensate(String transactionId) {
        LedgerEntry entry = entriesByTransactionId.get(transactionId);
        if (entry == null) {
            return false;
        }
        List<LedgerEntry> accountEntries = entriesByAccount.get(entry.accountId());
        if (!accountEntries.get(accountEntries.size() - 1).equals(entry)) {
            throw new IllegalStateException("Only the latest entry of an account can be compensated");
        }
        accountEntries.remove(accountEntries.size() - 1);
        entriesByTransactionId.remove(transactionId);
        balances.put(entry.accountId(), entry.balanceBefore());
        if (entry.currencyAssigned()) {
            currencies.remove(entry.accountId());
        }
        if (entry.reversesTransactionId() != null) {
            reversedTransactionIds.remove(entry.reversesTransactionId());
        }
        return true;
    }

    @Override
    public synchronized BigDecimal balanceOf(String accountId) {
        return balances.getOrDefault(accountId, ZERO);
    }

    @Override
    public synchronized Map<String, BigDecimal> balances() {
        return new LinkedHashMap<>(balances);
    }

    @Override
    public synchronized List<LedgerEntry> entriesFor(String accountId) {
        return List.copyOf(entriesByAccount.getOrDefault(accountId, List.of()));
    }
}
