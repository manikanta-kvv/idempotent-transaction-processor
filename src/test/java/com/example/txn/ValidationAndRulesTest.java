package com.example.txn;

import com.example.txn.model.TransactionRecord;
import com.example.txn.model.TransactionRequest;
import com.example.txn.model.TransactionStatus;
import com.example.txn.report.ProcessingSummary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.example.txn.Harness.credit;
import static com.example.txn.Harness.debit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationAndRulesTest {

    @Test
    void invalidRecordsAreRejectedAndDoNotStopTheBatch() {
        Harness h = new Harness();
        TransactionRecord negative = h.send(credit("BAD1", "ACC-1", "-5.00", 1));
        TransactionRecord missingId = h.send(new TransactionRequest(null, "REQ-X", "ACC-1", "CREDIT",
                "5.00", "INR", "1", null, null));
        TransactionRecord badType = h.send(TransactionRequest.of("BAD3", "REQ-BAD3", "ACC-1", "TRANSFER",
                "5.00", "INR", 1));
        TransactionRecord good = h.send(credit("GOOD", "ACC-1", "5.00", 1));

        assertEquals(TransactionStatus.REJECTED, negative.status());
        assertEquals(TransactionStatus.REJECTED, missingId.status());
        assertEquals(TransactionStatus.REJECTED, badType.status());
        assertEquals(TransactionStatus.PROCESSED, good.status());
        assertEquals(new BigDecimal("5.00"), h.ledger.balanceOf("ACC-1"));

        ProcessingSummary summary = h.processor.summary();
        assertEquals(3, summary.count(TransactionStatus.REJECTED));
        assertEquals(4, summary.totalReceived());
    }

    @Test
    void invalidRecordDoesNotBurnTheTransactionIdSoACorrectedResendIsAccepted() {
        Harness h = new Harness();
        h.send(credit("T1", "ACC-1", "abc", 1));
        TransactionRecord corrected = h.send(credit("T1", "ACC-1", "10.00", 1));

        assertEquals(TransactionStatus.PROCESSED, corrected.status());
    }

    @Test
    void invalidReasonNeverContainsTheSubmittedValue() {
        Harness h = new Harness();
        TransactionRecord rec = h.send(credit("T1", "ACC-1", "SECRET-VALUE", 1));

        assertFalse(rec.reason().contains("SECRET-VALUE"));
        assertTrue(rec.reason().contains("amount"));
    }

    @Test
    void identifiersWithControlCharactersAreSanitisedInTheRecord() {
        Harness h = new Harness();
        TransactionRecord rec = h.send(new TransactionRequest("T1\nFAKE-LOG-LINE", "R1", "ACC-1", "CREDIT",
                "5.00", "INR", "1", null, null));

        assertEquals("<invalid>", rec.transactionId());
    }

    @Test
    void insufficientFundsIsRejectedWithoutChangingBalanceAndLaterSequencesContinue() {
        Harness h = new Harness();
        TransactionRecord overdraw = h.send(debit("T1", "ACC-1", "100.00", 1));
        TransactionRecord next = h.send(credit("T2", "ACC-1", "40.00", 2));

        assertEquals(TransactionStatus.REJECTED, overdraw.status());
        assertEquals("INSUFFICIENT_FUNDS", overdraw.reason());
        assertEquals(TransactionStatus.PROCESSED, next.status());
        assertEquals(new BigDecimal("40.00"), h.ledger.balanceOf("ACC-1"));
    }

    @Test
    void reversalUndoesTheOriginalOnceOnly() {
        Harness h = new Harness();
        h.send(credit("T1", "ACC-1", "100.00", 1));
        TransactionRecord reversal = h.send(TransactionRequest.reversal("R1", "REQ-R1", "ACC-1", "100.00", "INR", 2, "T1"));
        TransactionRecord again = h.send(TransactionRequest.reversal("R2", "REQ-R2", "ACC-1", "100.00", "INR", 3, "T1"));

        assertEquals(TransactionStatus.PROCESSED, reversal.status());
        assertEquals(TransactionStatus.REJECTED, again.status());
        assertEquals("ALREADY_REVERSED", again.reason());
        assertEquals(new BigDecimal("0.00"), h.ledger.balanceOf("ACC-1"));
    }

    @Test
    void reversalOfUnknownTransactionIsRejected() {
        Harness h = new Harness();
        TransactionRecord reversal = h.send(TransactionRequest.reversal("R1", "REQ-R1", "ACC-1", "10.00", "INR", 1, "NOPE"));

        assertEquals(TransactionStatus.REJECTED, reversal.status());
        assertEquals("ORIGINAL_NOT_POSTED", reversal.reason());
    }

    @Test
    void currencyMismatchOnAnExistingAccountIsRejected() {
        Harness h = new Harness();
        h.send(credit("T1", "ACC-1", "10.00", 1));
        TransactionRecord usd = h.send(TransactionRequest.of("T2", "REQ-T2", "ACC-1", "CREDIT", "10.00", "USD", 2));

        assertEquals(TransactionStatus.REJECTED, usd.status());
        assertEquals("CURRENCY_MISMATCH", usd.reason());
    }
}
