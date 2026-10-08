package com.example.txn.util;

/** Keeps customer identifiers out of logs and reports. */
public final class Masking {
    private Masking() {}

    /**
     * Makes an identifier from untrusted input safe to log or print: returns it unchanged when it looks
     * like a normal id, otherwise a placeholder. This also prevents log injection (newlines, control chars).
     */
    public static String safeId(String value) {
        if (value == null || value.isBlank()) {
            return "<missing>";
        }
        return value.matches("[A-Za-z0-9_-]{1,64}") ? value : "<invalid>";
    }

    /** ACC-1001 -> ****1001. Short or missing values are fully masked. */
    public static String maskAccount(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return "<missing>";
        }
        if (accountId.length() <= 4) {
            return "****";
        }
        return "****" + accountId.substring(accountId.length() - 4);
    }
}
