package com.example.txn;

import com.example.txn.ingest.CsvTransactionParser;
import com.example.txn.model.TransactionRequest;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CsvTransactionParserTest {

    @Test
    void skipsHeaderCommentsAndBlankLines() {
        String csv = """
                # comment
                transactionId,requestId,accountId,transactionType,amount,currency,sequenceNumber,eventTime,originalTransactionId

                T1,R1,ACC-1,CREDIT,10.00,INR,1,2026-10-08T10:00:00Z,
                """;
        List<TransactionRequest> requests = CsvTransactionParser.parse(new StringReader(csv));

        assertEquals(1, requests.size());
        assertEquals("T1", requests.get(0).transactionId());
        assertEquals("2026-10-08T10:00:00Z", requests.get(0).eventTime());
    }

    @Test
    void shortLinesAreKeptWithMissingFieldsSoTheValidatorCanRejectThem() {
        List<TransactionRequest> requests = CsvTransactionParser.parse(new StringReader("T1,R1,ACC-1\n"));

        assertEquals(1, requests.size());
        assertNull(requests.get(0).amount());
    }
}
