package com.example.txn.validation;

import com.example.txn.model.Transaction;

import java.util.List;

/** Either a valid typed transaction, or a list of error messages (never the raw values). */
public record ValidationResult(Transaction transaction, List<String> errors) {

    public boolean isValid() {
        return errors.isEmpty();
    }
}
