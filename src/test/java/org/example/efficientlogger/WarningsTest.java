package org.example.efficientlogger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WarningsTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void ioErrorReportsAreLimitedToOnePerTenSeconds() {
        Warnings w = new Warnings((message, cause) -> {});
        assertEquals(0, w.admitIoErrorReport(0));
        assertEquals(-1, w.admitIoErrorReport(1));
        assertEquals(-1, w.admitIoErrorReport(10 * SECOND - 1));
        assertEquals(2, w.admitIoErrorReport(10 * SECOND), "reports how many were held back");
        assertEquals(-1, w.admitIoErrorReport(15 * SECOND));
        assertEquals(1, w.admitIoErrorReport(25 * SECOND));
    }

    @Test
    void ioErrorLimitWorksAcrossNanoTimeOverflow() {
        Warnings w = new Warnings((message, cause) -> {});
        long start = Long.MAX_VALUE - SECOND;
        assertEquals(0, w.admitIoErrorReport(start));
        assertEquals(-1, w.admitIoErrorReport(start + 5 * SECOND)); // wrapped to negative
        assertEquals(1, w.admitIoErrorReport(start + 10 * SECOND));
    }

    @Test
    void invalidIdReportsAreNumberedUpTo100() {
        Warnings w = new Warnings((message, cause) -> {});
        for (int i = 1; i <= 100; i++) {
            assertEquals(i, w.admitInvalidIdReport());
        }
        assertEquals(0, w.admitInvalidIdReport());
        assertEquals(0, w.admitInvalidIdReport());
    }

    @Test
    void thrashIsReportedOnce() {
        Warnings w = new Warnings((message, cause) -> {});
        assertTrue(w.admitThrashReport());
        assertFalse(w.admitThrashReport());
    }

    @Test
    void aThrowingHandlerIsIgnored() {
        List<String> seen = new ArrayList<>();
        Warnings w = new Warnings((message, cause) -> {
            seen.add(message);
            throw new IllegalStateException("broken handler");
        });
        w.emitAll(List.of(new Warnings.Pending("one", null), new Warnings.Pending("two", null)));
        w.emitAll(null);
        assertEquals(List.of("one", "two"), seen);
    }
}
