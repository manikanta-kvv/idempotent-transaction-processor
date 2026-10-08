package com.example.txn.validation;

import com.example.txn.model.Transaction;
import com.example.txn.model.TransactionRequest;
import com.example.txn.model.TransactionType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates a raw request and converts it into a typed {@link Transaction}.
 * Error messages name the field and the rule only. They never echo the submitted value, so
 * invalid (and possibly sensitive) input does not leak into logs.
 */
public class TransactionValidator {

    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000");

    public ValidationResult validate(TransactionRequest r) {
        List<String> errors = new ArrayList<>();

        String transactionId = id("transactionId", r.transactionId(), errors);
        String requestId = id("requestId", r.requestId(), errors);
        String accountId = id("accountId", r.accountId(), errors);
        TransactionType type = type(r.transactionType(), errors);
        BigDecimal amount = amount(r.amount(), errors);
        String currency = currency(r.currency(), errors);
        Long sequence = sequence(r.sequenceNumber(), errors);
        Instant eventTime = eventTime(r.eventTime(), errors);

        String original = null;
        if (type == TransactionType.REVERSAL) {
            original = id("originalTransactionId", r.originalTransactionId(), errors);
        }

        if (!errors.isEmpty()) {
            return new ValidationResult(null, List.copyOf(errors));
        }
        return new ValidationResult(new Transaction(transactionId, requestId, accountId, type, amount,
                currency, sequence, eventTime, original), List.of());
    }

    private static String id(String field, String value, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add(field + ": required");
            return null;
        }
        if (!ID.matcher(value.trim()).matches()) {
            errors.add(field + ": must be 1-64 characters of letters, digits, '-' or '_'");
            return null;
        }
        return value.trim();
    }

    private static TransactionType type(String value, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add("transactionType: required");
            return null;
        }
        try {
            return TransactionType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            errors.add("transactionType: must be one of CREDIT, DEBIT, REVERSAL");
            return null;
        }
    }

    private static BigDecimal amount(String value, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add("amount: required");
            return null;
        }
        try {
            BigDecimal amount = new BigDecimal(value.trim());
            if (amount.signum() <= 0) {
                errors.add("amount: must be greater than zero");
                return null;
            }
            if (amount.stripTrailingZeros().scale() > 2) {
                errors.add("amount: at most 2 decimal places allowed");
                return null;
            }
            if (amount.compareTo(MAX_AMOUNT) > 0) {
                errors.add("amount: exceeds maximum allowed value");
                return null;
            }
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (NumberFormatException e) {
            errors.add("amount: must be a number");
            return null;
        }
    }

    private static String currency(String value, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add("currency: required");
            return null;
        }
        if (!CURRENCY.matcher(value.trim()).matches()) {
            errors.add("currency: must be a 3-letter upper-case code");
            return null;
        }
        return value.trim();
    }

    private static Long sequence(String value, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add("sequenceNumber: required");
            return null;
        }
        try {
            long sequence = Long.parseLong(value.trim());
            if (sequence < 1) {
                errors.add("sequenceNumber: must be 1 or greater");
                return null;
            }
            return sequence;
        } catch (NumberFormatException e) {
            errors.add("sequenceNumber: must be a whole number");
            return null;
        }
    }

    private static Instant eventTime(String value, List<String> errors) {
        if (value == null || value.isBlank()) {
            return null; // optional, used for audit only
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            errors.add("eventTime: must be an ISO-8601 instant, e.g. 2026-10-08T10:00:00Z");
            return null;
        }
    }
}
