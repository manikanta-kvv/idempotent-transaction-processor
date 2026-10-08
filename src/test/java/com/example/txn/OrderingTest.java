package com.example.txn;

import com.example.txn.ledger.LedgerEntry;
import com.example.txn.model.TransactionRecord;
import com.example.txn.model.TransactionStatus;
import com.example.txn.report.ProcessingSummary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.example.txn.Harness.credit;
import static com.example.txn.Harness.debit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderingTest {

    @Test
    void requestsArrivingInReverseOrderAreProcessedInSequenceOrder() {
        Harness h = new Harness();
        TransactionRecord t3 = h.send(debit("T3", "ACC-1", "200.00", 3));
        TransactionRecord t2 = h.send(credit("T2", "ACC-1", "500.00", 2));
        assertEquals(TransactionStatus.PENDING_SEQUENCE, t3.status());
        assertEquals(TransactionStatus.PENDING_SEQUENCE, t2.status());

        h.send(credit("T1", "ACC-1", "1000.00", 1));

        assertEquals(TransactionStatus.PROCESSED, t3.status());
        assertEquals(TransactionStatus.PROCESSED, t2.status());
        List<String> order = h.ledger.entriesFor("ACC-1").stream().map(LedgerEntry::transactionId).toList();
        assertEquals(List.of("T1", "T2", "T3"), order);
        assertEquals(new BigDecimal("1300.00"), h.ledger.balanceOf("ACC-1"));
    }

    @Test
    void debitThatOnlyWorksInTheCorrectOrderSucceedsEvenWhenItArrivesFirst() {
        Harness h = new Harness();
        TransactionRecord debit = h.send(debit("T2", "ACC-1", "80.00", 2)); // would fail with balance 0
        h.send(credit("T1", "ACC-1", "100.00", 1));

        assertEquals(TransactionStatus.PROCESSED, debit.status());
        assertEquals(new BigDecimal("20.00"), h.ledger.balanceOf("ACC-1"));
    }

    @Test
    void missingSequenceKeepsLaterRequestPendingAndIsReported() {
        Harness h = new Harness();
        h.send(credit("T1", "ACC-1", "10.00", 1));
        TransactionRecord t3 = h.send(credit("T3", "ACC-1", "30.00", 3));

        ProcessingSummary summary = h.processor.summary();
        assertEquals(TransactionStatus.PENDING_SEQUENCE, t3.status());
        assertEquals(List.of(2L), summary.missingSequences().get("ACC-1"));
        assertEquals(new BigDecimal("10.00"), h.ledger.balanceOf("ACC-1"));

        h.send(credit("T2", "ACC-1", "20.00", 2)); // the gap is filled later
        assertEquals(TransactionStatus.PROCESSED, t3.status());
        assertTrue(h.processor.summary().missingSequences().isEmpty());
        assertEquals(new BigDecimal("60.00"), h.ledger.balanceOf("ACC-1"));
    }

    @Test
    void accountsAreOrderedIndependently() {
        Harness h = new Harness();
        h.send(credit("A2", "ACC-A", "20.00", 2)); // ACC-A waits
        TransactionRecord b1 = h.send(credit("B1", "ACC-B", "5.00", 1));

        assertEquals(TransactionStatus.PROCESSED, b1.status(), "ACC-B must not wait for ACC-A");
        assertEquals(new BigDecimal("5.00"), h.ledger.balanceOf("ACC-B"));
    }

    @Test
    void differentTransactionWithAnAlreadyUsedSequenceIsRejected() {
        Harness h = new Harness();
        h.send(credit("T1", "ACC-1", "10.00", 1));
        TransactionRecord clash = h.send(credit("T1B", "ACC-1", "10.00", 1));

        assertEquals(TransactionStatus.REJECTED, clash.status());
        assertEquals("SEQUENCE_ALREADY_CONSUMED", clash.reason());
        assertEquals(new BigDecimal("10.00"), h.ledger.balanceOf("ACC-1"));
    }

    @Test
    void twoDifferentTransactionsWaitingForTheSameSequenceConflict() {
        Harness h = new Harness();
        h.send(credit("T5", "ACC-1", "10.00", 5));
        TransactionRecord clash = h.send(credit("T5B", "ACC-1", "10.00", 5));

        assertEquals(TransactionStatus.REJECTED, clash.status());
        assertEquals("SEQUENCE_CONFLICT", clash.reason());
    }
}
