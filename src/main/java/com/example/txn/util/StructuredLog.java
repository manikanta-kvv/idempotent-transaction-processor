package com.example.txn.util;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tiny structured-logging helper: one line per event in key=value form, easy to grep and easy to
 * ship to a log platform later. Uses java.util.logging so the project has no runtime dependencies.
 */
public final class StructuredLog {
    private StructuredLog() {}

    public static void info(Logger log, String event, Object... keyValues) {
        log(log, Level.INFO, event, keyValues);
    }

    public static void warn(Logger log, String event, Object... keyValues) {
        log(log, Level.WARNING, event, keyValues);
    }

    private static void log(Logger log, Level level, String event, Object... keyValues) {
        if (!log.isLoggable(level)) {
            return;
        }
        StringBuilder sb = new StringBuilder("event=").append(event);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            sb.append(' ').append(keyValues[i]).append('=').append(keyValues[i + 1]);
        }
        log.log(level, sb.toString());
    }
}
