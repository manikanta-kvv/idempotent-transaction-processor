package com.example.txn;

import com.example.txn.util.Masking;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MaskingTest {

    @Test
    void masksAllButTheLastFourCharacters() {
        assertEquals("****1001", Masking.maskAccount("ACC-1001"));
        assertEquals("****", Masking.maskAccount("AB"));
        assertEquals("<missing>", Masking.maskAccount(null));
    }

    @Test
    void safeIdReplacesUnsafeValues() {
        assertEquals("T-1", Masking.safeId("T-1"));
        assertEquals("<invalid>", Masking.safeId("T1\nFORGED"));
        assertEquals("<missing>", Masking.safeId("  "));
    }
}
