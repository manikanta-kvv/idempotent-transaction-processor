package com.example.txn.processing;

/** A permanent business failure (for example insufficient funds). Retrying cannot help. */
public class BusinessRuleException extends RuntimeException {

    private final String code;

    public BusinessRuleException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
