package com.example.txn.processing;

/**
 * Retry settings: total attempts per processing run and exponential back-off between attempts.
 *
 * @param maxAttempts          total attempts (first try included), at least 1
 * @param initialBackoffMillis wait after the first failed attempt
 * @param multiplier           factor applied for every further failed attempt
 */
public record RetryPolicy(int maxAttempts, long initialBackoffMillis, double multiplier) {

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
    }

    public static RetryPolicy defaults() {
        return new RetryPolicy(3, 100, 2.0);
    }

    /** Wait time after the given failed attempt (1-based). */
    public long backoffMillis(int failedAttempt) {
        return Math.round(initialBackoffMillis * Math.pow(multiplier, failedAttempt - 1));
    }
}
