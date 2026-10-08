package com.example.txn.processing;

/** Abstraction over waiting so that tests can run retries without real delays. */
@FunctionalInterface
public interface Sleeper {

    void sleep(long millis);

    static Sleeper real() {
        return millis -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }
}
