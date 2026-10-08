package com.example.txn;

import com.example.txn.model.TransactionRecord;
import com.example.txn.model.TransactionStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.example.txn.Harness.credit;
import static com.example.txn.Harness.debit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryTest {

    @Test
    void transientFailureIsRetriedAndEffectIsAppliedExactlyOnce() {
        Harness h = new Harness(Map.of("T1", 2)); // fails twice, succeeds on the 3rd attempt
        TransactionRecord rec = h.send(credit("T1", "ACC-1", "100.00", 1));

        assertEquals(TransactionStatus.PROCESSED, rec.status());
        assertEquals(3, rec.attempts());
        assertEquals(new BigDecimal("100.00"), h.ledger.balanceOf("ACC-1"), "balance must not be applied 3 times");
        assertEquals(1, h.ledger.entriesFor("ACC-1").size());
        assertEquals(List.of("T1"), List.copyOf(h.publisher.deliveredTransactionIds()));
        assertEquals(List.of(100L, 200L), h.sleeps, "exponential back-off between attempts");
        assertTrue(rec.history().contains(TransactionStatus.RETRY_PENDING));
    }

    @Test
    void exhaustedRetriesEndInFailedWithNoPartialEffect() {
        Harness h = new Harness(Map.of("T1", 10));
        TransactionRecord rec = h.send(credit("T1", "ACC-1", "100.00", 1));

        assertEquals(TransactionStatus.FAILED, rec.status());
        assertEquals("RETRIES_EXHAUSTED", rec.reason());
        assertEquals(3, rec.attempts());
        assertEquals(new BigDecimal("0.00"), h.ledger.balanceOf("ACC-1"), "posting was compensated");
        assertTrue(h.ledger.entriesFor("ACC-1").isEmpty());
        assertTrue(h.publisher.deliveredTransactionIds().isEmpty());
    }

    @Test
    void failedTransactionBlocksLaterSequencesOfThatAccountOnly() {
        Harness h = new Harness(Map.of("T1", 10));
        h.send(credit("T1", "ACC-1", "100.00", 1));
        TransactionRecord blocked = h.send(debit("T2", "ACC-1", "10.00", 2));
        TransactionRecord otherAccount = h.send(credit("X1", "ACC-2", "5.00", 1));

        assertEquals(TransactionStatus.PENDING_SEQUENCE, blocked.status());
        assertTrue(blocked.reason().startsWith("BLOCKED_BY_FAILED_SEQUENCE_1"));
        assertEquals(TransactionStatus.PROCESSED, otherAccount.status());
        assertTrue(h.processor.summary().blockedAccounts().containsKey("ACC-1"));
    }

    @Test
    void recoveryRetriesTheFailedTransactionThenReleasesTheWaitingOnes() {
        Harness h = new Harness(Map.of("T1", 4)); // 3 failures exhaust the retries, 4th call fails, 5th ok
        TransactionRecord t1 = h.send(credit("T1", "ACC-1", "100.00", 1));
        TransactionRecord t2 = h.send(debit("T2", "ACC-1", "30.00", 2));
        assertEquals(TransactionStatus.FAILED, t1.status());

        int recovered = h.processor.retryFailed();

        assertEquals(1, recovered);
        assertEquals(TransactionStatus.PROCESSED, t1.status());
        assertEquals(TransactionStatus.PROCESSED, t2.status());
        assertEquals(new BigDecimal("70.00"), h.ledger.balanceOf("ACC-1"));
        assertEquals(1, h.ledger.entriesFor("ACC-1").stream().filter(e -> e.transactionId().equals("T1")).count());
        assertTrue(h.processor.summary().blockedAccounts().isEmpty());
    }

    @Test
    void retryAfterLedgerCommitDoesNotPostTwice() {
        // The ledger commit succeeds, the downstream call fails: the retry must see ALREADY_POSTED.
        Harness h = new Harness(Map.of("T1", 1));
        TransactionRecord rec = h.send(credit("T1", "ACC-1", "100.00", 1));

        assertEquals(TransactionStatus.PROCESSED, rec.status());
        assertEquals("COMPLETED_AFTER_RETRY", rec.reason());
        assertEquals(new BigDecimal("100.00"), h.ledger.balanceOf("ACC-1"));
    }

    @Test
    void recoveryCanBeCalledRepeatedlyWithoutSideEffects() {
        Harness h = new Harness();
        h.send(credit("T1", "ACC-1", "100.00", 1));

        assertEquals(0, h.processor.retryFailed());
        assertEquals(0, h.processor.retryFailed());
        assertEquals(new BigDecimal("100.00"), h.ledger.balanceOf("ACC-1"));
    }
}
