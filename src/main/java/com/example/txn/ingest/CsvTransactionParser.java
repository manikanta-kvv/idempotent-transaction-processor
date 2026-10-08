package com.example.txn.ingest;

import com.example.txn.model.TransactionRequest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the CSV test input. Columns:
 * transactionId,requestId,accountId,transactionType,amount,currency,sequenceNumber,eventTime,originalTransactionId
 *
 * <p>The parser never judges the content: a bad number or a missing value is passed on as text so the
 * validator can reject that single record. Lines that are blank or start with '#' are ignored.
 * Quoted fields are not supported (not needed for this data set).
 */
public final class CsvTransactionParser {

    private static final int COLUMNS = 9;

    private CsvTransactionParser() {}

    public static List<TransactionRequest> parse(Reader reader) {
        List<TransactionRequest> requests = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(reader)) {
            String line;
            boolean headerChecked = false;
            while ((line = in.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (!headerChecked) {
                    headerChecked = true;
                    if (trimmed.toLowerCase().startsWith("transactionid,")) {
                        continue; // header row
                    }
                }
                requests.add(toRequest(trimmed));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read transaction input", e);
        }
        return requests;
    }

    public static List<TransactionRequest> parse(Path file) {
        try {
            return parse(Files.newBufferedReader(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not open transaction input file", e);
        }
    }

    public static List<TransactionRequest> parseClasspath(String resource) {
        InputStream stream = CsvTransactionParser.class.getResourceAsStream(resource);
        if (stream == null) {
            throw new IllegalArgumentException("Input resource not found: " + resource);
        }
        return parse(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }

    private static TransactionRequest toRequest(String line) {
        String[] parts = line.split(",", -1);
        String[] f = new String[COLUMNS];
        for (int i = 0; i < COLUMNS; i++) {
            f[i] = i < parts.length ? parts[i].trim() : null; // missing columns become null
        }
        return new TransactionRequest(f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7], f[8]);
    }
}
