package com.example.txn;

import com.example.txn.model.TransactionRecord;
import com.example.txn.model.TransactionRequest;
import com.example.txn.model.TransactionStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.example.txn.Harness.credit;
import static org.junit.jupiter.api.Assertions.assertEquals;

class IdempotencyTest {

    @Test
    void sameRequestSentTwiceIsAppliedOnce() {
        Harness h = new Harness();
        TransactionRecord first = h.send(credit("T1", "ACC-1", "100.00", 1));
        TransactionRecord second = h.send(credit("T1", "ACC-1", "100.00", 1));

        assertEquals(TransactionStatus.PROCESSED, first.status());
        assertEquals(TransactionStatus.DUPLICATE, second.status());
        assertEquals(new BigDecimal("100.00"), h.ledger.balanceOf("ACC-1"));
        assertEquals(1, h.ledger.entriesFor("ACC-1").size());
        assertEquals(1, h.publisher.callCount(), "downstream side effect must also happen once");
    }

    @Test
    void duplicateArrivingMuchLaterInTheStreamIsStillDetected() {
        Harness h = new Harness();
        h.send(credit("T1", "ACC-1", "100.00", 1));
        h.send(credit("T2", "ACC-1", "50.00", 2));
        h.send(credit("T3", "ACC-1", "25.00", 3));
        TransactionRecord late = h.send(credit("T1", "ACC-1", "100.00", 1));

        assertEquals(TransactionStatus.DUPLICATE, late.status());
        assertEquals(new BigDecimal("175.00"), h.ledger.balanceOf("ACC-1"));
    }

    @Test
    void sameTransactionIdWithNewRequestIdIsDuplicate() {
        Harness h = new Harness();
        h.send(credit("T1", "ACC-1", "100.00", 1));
        TransactionRecord retry = h.send(TransactionRequest.of("T1", "REQ-OTHER", "ACC-1", "CREDIT",
                "100.00", "INR", 1));

        assertEquals(TransactionStatus.DUPLICATE, retry.status());
        assertEquals("DUPLICATE_TRANSACTION_ID", retry.reason());
        assertEquals(new BigDecimal("100.00"), h.ledger.balanceOf("ACC-1"));
    }

    @Test
    void reusedRequestIdForADifferentTransactionIsDuplicate() {
        Harness h = new Harness();
        h.send(credit("T1", "ACC-1", "100.00", 1));
        TransactionRecord reused = h.send(TransactionRequest.of("T2", "REQ-T1", "ACC-1", "CREDIT",
                "999.00", "INR", 2));

        assertEquals(TransactionStatus.DUPLICATE, reused.status());
        assertEquals("DUPLICATE_REQUEST_ID", reused.reason());
        assertEquals(new BigDecimal("100.00"), h.ledger.balanceOf("ACC-1"));
    }

    @Test
    void sameTransactionIdWithDifferentPayloadIsDuplicateAndFlagged() {
        Harness h = new Harness();
        h.send(credit("T1", "ACC-1", "100.00", 1));
        TransactionRecord conflicting = h.send(credit("T1", "ACC-1", "999.00", 1));

        assertEquals(TransactionStatus.DUPLICATE, conflicting.status());
        assertEquals("DUPLICATE_TRANSACTION_ID_DIFFERENT_PAYLOAD", conflicting.reason());
        assertEquals(new BigDecimal("100.00"), h.ledger.balanceOf("ACC-1"));
    }

    @Test
    void duplicateOfAPendingTransactionDoesNotCreateASecondBufferedEntry() {
        Harness h = new Harness();
        h.send(credit("T2", "ACC-1", "50.00", 2)); // waits for seq 1
        TransactionRecord dup = h.send(credit("T2", "ACC-1", "50.00", 2));
        h.send(credit("T1", "ACC-1", "100.00", 1));

        assertEquals(TransactionStatus.DUPLICATE, dup.status());
        assertEquals(new BigDecimal("150.00"), h.ledger.balanceOf("ACC-1"));
        assertEquals(2, h.ledger.entriesFor("ACC-1").size());
    }
}
