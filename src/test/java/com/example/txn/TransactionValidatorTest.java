package com.example.txn;

import com.example.txn.model.TransactionRequest;
import com.example.txn.model.TransactionType;
import com.example.txn.validation.TransactionValidator;
import com.example.txn.validation.ValidationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionValidatorTest {

    private final TransactionValidator validator = new TransactionValidator();

    private static TransactionRequest request(String amount, String currency, String sequence, String type) {
        return new TransactionRequest("T1", "R1", "ACC-1", type, amount, currency, sequence, null, null);
    }

    @Test
    void validRequestBecomesATypedTransaction() {
        ValidationResult result = validator.validate(request("10.5", "INR", "3", "credit"));

        assertTrue(result.isValid());
        assertEquals(TransactionType.CREDIT, result.transaction().type());
        assertEquals(new BigDecimal("10.50"), result.transaction().amount());
        assertEquals(3L, result.transaction().sequenceNumber());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "1.234", "", "2000000000"})
    void badAmountsAreRejected(String amount) {
        assertFalse(validator.validate(request(amount, "INR", "1", "CREDIT")).isValid());
    }

    @ParameterizedTest
    @ValueSource(strings = {"inr", "IN", "INRR", "", "12A"})
    void badCurrenciesAreRejected(String currency) {
        assertFalse(validator.validate(request("1.00", currency, "1", "CREDIT")).isValid());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-3", "x", "", "1.5"})
    void badSequenceNumbersAreRejected(String sequence) {
        assertFalse(validator.validate(request("1.00", "INR", sequence, "CREDIT")).isValid());
    }

    @Test
    void reversalRequiresTheOriginalTransactionId() {
        ValidationResult result = validator.validate(request("1.00", "INR", "1", "REVERSAL"));

        assertFalse(result.isValid());
        assertTrue(result.errors().get(0).startsWith("originalTransactionId"));
    }

    @Test
    void allProblemsAreReportedTogether() {
        ValidationResult result = validator.validate(new TransactionRequest(null, null, null, null, null, null,
                null, "yesterday", null));

        assertTrue(result.errors().size() >= 7);
    }

    @Test
    void missingColumnsAreRejectedNotThrown() {
        assertFalse(validator.validate(new TransactionRequest(null, null, null, null, null, null, null, null, null))
                .isValid());
    }
}
