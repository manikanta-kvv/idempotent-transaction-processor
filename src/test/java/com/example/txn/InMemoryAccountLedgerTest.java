package com.example.txn;

import com.example.txn.ledger.AccountLedger.PostResult;
import com.example.txn.ledger.InMemoryAccountLedger;
import com.example.txn.model.Transaction;
import com.example.txn.model.TransactionType;
import com.example.txn.processing.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryAccountLedgerTest {

    private static Transaction tx(String id, TransactionType type, String amount, long seq) {
        return new Transaction(id, "R-" + id, "ACC-1", type, new BigDecimal(amount).setScale(2), "INR", seq, null,
                type == TransactionType.REVERSAL ? "T1" : null);
    }

    @Test
    void postingTheSameTransactionTwiceChangesNothingTheSecondTime() {
        InMemoryAccountLedger ledger = new InMemoryAccountLedger();
        assertEquals(PostResult.APPLIED, ledger.post(tx("T1", TransactionType.CREDIT, "50", 1)));
        assertEquals(PostResult.ALREADY_POSTED, ledger.post(tx("T1", TransactionType.CREDIT, "50", 1)));

        assertEquals(new BigDecimal("50.00"), ledger.balanceOf("ACC-1"));
        assertEquals(1, ledger.entriesFor("ACC-1").size());
    }

    @Test
    void rejectedPostingLeavesNoPartialState() {
        InMemoryAccountLedger ledger = new InMemoryAccountLedger();
        BusinessRuleException e = assertThrows(BusinessRuleException.class,
                () -> ledger.post(tx("T1", TransactionType.DEBIT, "50", 1)));

        assertEquals("INSUFFICIENT_FUNDS", e.code());
        assertTrue(ledger.entriesFor("ACC-1").isEmpty());
        assertEquals(new BigDecimal("0.00"), ledger.balanceOf("ACC-1"));
        assertTrue(ledger.balances().isEmpty());
    }

    @Test
    void compensateRestoresBalanceAndAllowsReposting() {
        InMemoryAccountLedger ledger = new InMemoryAccountLedger();
        ledger.post(tx("T1", TransactionType.CREDIT, "50", 1));
        ledger.post(tx("T2", TransactionType.CREDIT, "25", 2));

        assertTrue(ledger.compensate("T2"));
        assertEquals(new BigDecimal("50.00"), ledger.balanceOf("ACC-1"));
        assertFalse(ledger.compensate("T2"), "second compensation is a no-op");
        assertEquals(PostResult.APPLIED, ledger.post(tx("T2", TransactionType.CREDIT, "25", 2)));
        assertEquals(new BigDecimal("75.00"), ledger.balanceOf("ACC-1"));
    }

    @Test
    void onlyTheLatestEntryOfAnAccountCanBeCompensated() {
        InMemoryAccountLedger ledger = new InMemoryAccountLedger();
        ledger.post(tx("T1", TransactionType.CREDIT, "50", 1));
        ledger.post(tx("T2", TransactionType.CREDIT, "25", 2));

        assertThrows(IllegalStateException.class, () -> ledger.compensate("T1"));
    }

    @Test
    void reversalMustMatchTheOriginalAmount() {
        InMemoryAccountLedger ledger = new InMemoryAccountLedger();
        ledger.post(tx("T1", TransactionType.CREDIT, "50", 1));

        BusinessRuleException e = assertThrows(BusinessRuleException.class,
                () -> ledger.post(tx("R1", TransactionType.REVERSAL, "40", 2)));
        assertEquals("REVERSAL_AMOUNT_MISMATCH", e.code());
    }
}
