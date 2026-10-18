package org.example.efficientlogger;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FileTableTest {

    private static final Path DIR = Paths.get("fake-logs").toAbsolutePath().normalize();
    private static final long MS = 1_000_000L;

    private final List<String> warningLog = new ArrayList<>();
    private final Warnings warnings = new Warnings(
            (message, cause) -> warningLog.add(cause == null ? message : message + " | " + cause.getMessage()));
    private FakeSinks sinks = new FakeSinks(DIR);
    private OpenFileBudget budget = new OpenFileBudget(1024);

    private FileTable table(int bufferBytes) {
        return table(bufferBytes, "\n", 1024 + FileTable.EXTRA_ENTRIES);
    }

    private FileTable table(int bufferBytes, String separator, int maxEntries) {
        return new FileTable(FileNamePattern.parse(DIR, "{id}.json"), separator.getBytes(US_ASCII),
                bufferBytes, maxEntries, sinks, budget, warnings);
    }

    /** Emits the table's queued warnings and returns everything emitted since the last call. */
    private List<String> warnings(FileTable t) {
        warnings.emitAll(t.takeWarnings());
        List<String> emitted = new ArrayList<>(warningLog);
        warningLog.clear();
        return emitted;
    }

    private static Path file(String id) {
        return DIR.resolve(id + ".json");
    }

    // ---- buffering ----

    @Test
    void buffersRecordsUntilFlushed() {
        FileTable t = table(8192);
        t.append("a", "{\"n\":1}", 0);
        t.append("a", "{\"n\":2}", 1);
        t.append("b", "{\"n\":3}", 2);

        assertTrue(sinks.writes.isEmpty());
        assertEquals(2, t.dirtyCount());
        assertEquals(0, t.written());

        t.flushDirty(3);

        assertEquals("{\"n\":1}\n{\"n\":2}\n", sinks.content(file("a")));
        assertEquals("{\"n\":3}\n", sinks.content(file("b")));
        assertEquals(2, sinks.writes.size(), "one write per file");
        assertEquals(3, t.written());
        assertEquals(0, t.dirtyCount());
        assertTrue(warnings(t).isEmpty());
    }

    @Test
    void holdsBuffersOnlyWhileFilesHaveUnwrittenRecords() {
        FileTable t = table(8192);
        t.append("a", "{}", 0);
        t.append("b", "{}", 0);
        assertEquals(0, t.pooledBuffers());

        t.flushDirty(1);
        assertEquals(2, t.pooledBuffers());

        t.append("c", "{}", 2);
        assertEquals(1, t.pooledBuffers(), "c reuses a pooled buffer");
    }

    @Test
    void firstDirtyNanosIsWhenTheOldestWaitingRecordArrived() {
        FileTable t = table(8192);
        t.append("a", "{}", 5);
        t.append("b", "{}", 7);
        t.append("a", "{}", 9);
        assertEquals(5, t.firstDirtyNanos());

        t.flushDirty(10);
        t.append("b", "{}", 20);
        assertEquals(20, t.firstDirtyNanos());
    }

    @Test
    void writesOnlyWholeRecords() {
        FileTable t = table(64);
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            String record = "{\"i\":" + i + ",\"pad\":\"xxxxxxxxxx\"}";
            t.append("a", record, i);
            expected.append(record).append('\n');
        }
        t.flushDirty(20);

        assertEquals(expected.toString(), sinks.content(file("a")));
        assertTrue(sinks.writes.size() > 1);
        for (FakeSinks.Write w : sinks.writes) {
            assertTrue(w.text().endsWith("\n"), "partial record written: " + w.text());
            assertTrue(w.bytes() <= 64);
        }
        assertEquals(20, t.written());
    }

    @Test
    void splitsOnlyRecordsLargerThanTheBuffer() {
        FileTable t = table(64);
        StringBuilder big = new StringBuilder("{\"text\":\"");
        while (big.length() < 1000) {
            big.append("abé東😀 "); // 1-, 2-, 3- and 4-byte UTF-8
        }
        big.append("\"}");

        t.append("a", "{\"small\":1}", 0);
        t.append("a", big.toString(), 1);
        t.append("a", "{\"small\":2}", 2);
        t.flushDirty(3);

        assertEquals("{\"small\":1}\n" + big + "\n{\"small\":2}\n", sinks.content(file("a")));
        assertEquals("{\"small\":1}\n", sinks.writes.get(0).text(), "the record before is written whole first");
        for (FakeSinks.Write w : sinks.writes) {
            assertTrue(w.bytes() <= 64);
        }
        assertEquals(3, t.written());
    }

    /** A record that ends exactly at the end of the buffer leaves no room for the next, even an empty one. */
    @Test
    void handlesABufferFilledToTheLastByte() {
        for (String separator : List.of("\n", "\r\n")) {
            for (String next : List.of("", "{}")) {
                sinks = new FakeSinks(DIR);
                FileTable t = table(64, separator, 1024 + FileTable.EXTRA_ENTRIES);
                String full = "x".repeat(64 - separator.length()); // with its separator, fills the buffer
                String half = "y".repeat(32 - separator.length());
                t.append("a", full, 0);
                t.append("a", next, 1);
                t.flushDirty(2);
                t.append("a", half, 3);
                t.append("a", half, 4); // full again
                t.append("a", next, 5);
                t.flushDirty(6);

                String expected = full + separator + next + separator + half + separator + half + separator
                        + next + separator;
                assertEquals(expected, sinks.content(file("a")), "separator " + separator.length() + ", next \"" + next + "\"");
                assertEquals(5, t.written());
                for (FakeSinks.Write w : sinks.writes) {
                    assertTrue(w.text().endsWith(separator), "partial record written: " + w.text());
                }
                t.closeAll(7);
            }
        }
    }

    // ---- opening files ----

    @Test
    void appendsToExistingFilesAndFinishesTornLines() {
        sinks.preset(file("complete"), "old\n");
        sinks.preset(file("torn"), "half a rec");
        sinks.preset(file("empty"), "");
        FileTable t = table(8192);
        for (String id : new String[] {"complete", "torn", "empty", "new"}) {
            t.append(id, "{}", 0);
        }
        t.flushDirty(1);

        assertEquals("old\n{}\n", sinks.content(file("complete")));
        assertEquals("half a rec\n{}\n", sinks.content(file("torn")));
        assertEquals("{}\n", sinks.content(file("empty")));
        assertEquals("{}\n", sinks.content(file("new")));
    }

    @Test
    void usesTheConfiguredSeparatorEverywhere() {
        sinks.preset(file("torn"), "half");
        FileTable t = table(8192, "\r\n", 100);
        t.append("torn", "{}", 0);
        t.append("torn", "{}", 0);
        t.flushDirty(1);

        assertEquals("half\r\n{}\r\n{}\r\n", sinks.content(file("torn")));
    }

    @Test
    void createsAMissingDirectory() {
        sinks = new FakeSinks(); // DIR doesn't exist
        FileTable t = table(8192);
        t.append("a", "{}", 0);
        t.flushDirty(1);

        assertTrue(sinks.directoryExists(DIR));
        assertEquals("{}\n", sinks.content(file("a")));
        assertTrue(warnings(t).isEmpty());
    }

    // ---- the open-file cap ----

    @Test
    void closesTheLeastRecentlyUsedFileAtTheOpenFileCap() {
        budget = new OpenFileBudget(2);
        FileTable t = table(8192);
        long now = 0;
        for (int round = 0; round < 3; round++) {
            for (String id : new String[] {"a", "b", "c"}) {
                t.append(id, "{\"r\":" + round + "}", now += 10 * MS);
            }
        }
        t.flushDirty(now);

        assertEquals(2, sinks.maxOpenNow);
        assertEquals(2, budget.open());
        for (String id : new String[] {"a", "b", "c"}) {
            assertEquals("{\"r\":0}\n{\"r\":1}\n{\"r\":2}\n", sinks.content(file(id)), id);
        }
        List<String> w = warnings(t);
        assertEquals(1, w.size(), w.toString());
        assertTrue(w.get(0).contains("maxOpenFiles=2"), w.get(0));
    }

    @Test
    void closingAnIdleFileIsNotThrashing() {
        budget = new OpenFileBudget(1);
        FileTable t = table(8192);
        t.append("a", "{}", 0);
        t.append("b", "{}", 2000 * MS);
        t.flushDirty(2000 * MS);

        assertEquals("{}\n", sinks.content(file("a")));
        assertTrue(warnings(t).isEmpty());
    }

    @Test
    void reopensAFileThatWasClosedToMakeRoom() {
        budget = new OpenFileBudget(1);
        FileTable t = table(8192);
        t.append("a", "{\"n\":1}", 0);
        t.append("b", "{}", 2000 * MS); // closes a; a was the cached last file
        t.append("a", "{\"n\":2}", 4000 * MS); // must reopen a, not write to the closed one
        t.flushDirty(4000 * MS);

        assertEquals("{\"n\":1}\n{\"n\":2}\n", sinks.content(file("a")));
        assertEquals(3, sinks.opens);
    }

    @Test
    void theCapIsSharedAndSoft() {
        budget = new OpenFileBudget(1);
        FileTable t1 = table(8192);
        FileTable t2 = table(8192);
        t1.append("a", "{}", 0);
        t2.append("b", "{}", 0);
        assertEquals(2, budget.open(), "t2 had no file of its own to close");

        t2.append("c", "{}", 1);
        assertEquals(2, budget.open(), "t2 closed b, its own least recently used file");
        assertEquals(1, t2.openCount());

        t1.closeAll(2);
        t2.closeAll(2);
        assertEquals(0, budget.open());
        assertEquals(0, sinks.openNow);
        assertEquals("{}\n", sinks.content(file("b")));
        assertEquals("{}\n", sinks.content(file("c")));
    }

    @Test
    void closesHalfItsFilesWhenTheOsRunsOutOfDescriptors() {
        sinks.osOpenLimit = 4;
        FileTable t = table(8192);
        for (int i = 0; i < 5; i++) {
            t.append("f" + i, "{}", i);
        }
        t.flushDirty(5);

        assertEquals(3, sinks.openNow, "closed 2 of 4, then opened f4");
        for (int i = 0; i < 5; i++) {
            assertEquals("{}\n", sinks.content(file("f" + i)));
        }
        List<String> w = warnings(t);
        assertEquals(1, w.size(), w.toString());
        assertTrue(w.get(0).startsWith("too many open files; closed 2"), w.get(0));
    }

    @Test
    void recognizesWindowsRunningOutOfFileHandles() {
        sinks.osOpenLimit = 4;
        sinks.osOpenLimitMessage = "The system cannot open the file";
        FileTable t = table(8192);
        for (int i = 0; i < 5; i++) {
            t.append("f" + i, "{}", i);
        }
        t.flushDirty(5);

        assertEquals(0, t.lostIoError());
        assertEquals("{}\n", sinks.content(file("f4")));
        List<String> w = warnings(t);
        assertEquals(1, w.size(), w.toString());
        assertTrue(w.get(0).startsWith("too many open files; closed 2"), w.get(0));
    }

    @Test
    void limitsEntriesThatHoldNoFile() {
        FileTable t = table(8192, "\n", 3);
        for (int i = 0; i < 10; i++) {
            t.append("bad/" + i, "{}", i);
        }
        t.append("ok", "{}", 10);
        t.flushDirty(11);

        assertEquals(3, t.entryCount());
        assertEquals("{}\n", sinks.content(file("ok")));
        assertEquals(10, t.droppedInvalidId());
    }

    // ---- invalid ids ----

    @Test
    void dropsInvalidIdsAndReportsEachOnce() {
        FileTable t = table(8192);
        t.append("a/b", "{}", 0);
        t.append("a/b", "{}", 1);
        t.append("ok", "{}", 2);
        t.flushDirty(3);

        assertEquals(2, t.droppedInvalidId());
        assertEquals(1, t.written());
        assertEquals(1, sinks.opens);
        List<String> w = warnings(t);
        assertEquals(1, w.size(), w.toString());
        assertTrue(w.get(0).contains("invalid id \"a/b\"") && w.get(0).contains("contains '/'"), w.get(0));
    }

    @Test
    void reportsAtMost100InvalidIds() {
        FileTable t = table(8192);
        for (int i = 0; i < 150; i++) {
            t.append("bad/" + i, "{}", i);
        }
        List<String> w = warnings(t);
        assertEquals(100, w.size());
        assertTrue(w.get(99).endsWith("(further invalid ids won't be reported)"), w.get(99));
        assertEquals(150, t.droppedInvalidId());
    }

    // ---- I/O errors ----

    @Test
    void aFailedWriteDropsTheBufferedRecordsAndBacksOff() {
        FileTable t = table(8192);
        t.append("a", "{\"n\":1}", 0);
        t.append("a", "{\"n\":2}", 0);
        sinks.failNextWrite(file("a"), new IOException("disk full"));

        t.flushDirty(0);
        assertEquals(2, t.lostIoError());
        assertEquals(0, t.written());
        assertEquals(0, sinks.openNow);
        assertEquals(0, budget.open());
        List<String> w = warnings(t);
        assertEquals(1, w.size(), w.toString());
        assertTrue(w.get(0).startsWith("can't write to " + file("a")) && w.get(0).endsWith("| disk full"), w.get(0));

        t.append("a", "{\"n\":3}", 999 * MS); // still backing off
        assertEquals(3, t.lostIoError());
        assertEquals(1, sinks.opens);

        t.append("a", "{\"n\":4}", 1000 * MS); // retry is due
        t.flushDirty(1000 * MS);
        assertEquals(2, sinks.opens);
        assertEquals("{\"n\":4}\n", sinks.content(file("a")));
        assertEquals(1, t.written());
    }

    @Test
    void aFailedOpenBacksOffThenRetries() {
        FileTable t = table(8192);
        sinks.failNextOpen(file("a"), new FileNotFoundException("a.json (Permission denied)"));

        t.append("a", "{\"n\":1}", 0);
        t.append("a", "{\"n\":2}", 999 * MS);
        assertEquals(2, t.lostIoError());
        assertEquals(0, sinks.opens);
        assertEquals(0, budget.open());

        t.append("a", "{\"n\":3}", 1000 * MS);
        t.flushDirty(1000 * MS);
        assertEquals("{\"n\":3}\n", sinks.content(file("a")));
        List<String> w = warnings(t);
        assertEquals(1, w.size(), w.toString());
        assertTrue(w.get(0).startsWith("can't open " + file("a")), w.get(0));
    }

    @Test
    void ioErrorReportsAreRateLimited() {
        FileTable t = table(8192);
        for (int i = 0; i < 5; i++) {
            sinks.failNextOpen(file("f" + i), new IOException("boom " + i));
            t.append("f" + i, "{}", i * MS);
        }
        List<String> w = warnings(t);
        assertEquals(1, w.size(), w.toString());
        assertTrue(w.get(0).endsWith("boom 0"), w.get(0));

        sinks.failNextOpen(file("g"), new IOException("later"));
        t.append("g", "{}", 10_000 * MS);
        w = warnings(t);
        assertEquals(1, w.size(), w.toString());
        assertTrue(w.get(0).contains("(4 more I/O errors were not reported)"), w.get(0));
    }

    /** An unchecked exception from a sink fails that file like an I/O error; it never escapes. */
    @Test
    void uncheckedSinkErrorsAreHandledLikeIoErrors() {
        FileTable t = table(8192);
        long s = 1000 * MS;
        t.append("b", "{}", 0);
        t.append("a", "{}", 0);
        sinks.failNextWrite(file("a"), new IllegalStateException("write bug"));
        t.flushDirty(0);
        assertEquals("{}\n", sinks.content(file("b")), "the other file is still written");
        assertEquals(1, t.lostIoError());
        assertEquals(List.of("can't write to " + file("a") + "; dropping its messages for 1 s | write bug"), warnings(t));

        sinks.failNextOpen(file("c"), new SecurityException("open bug"));
        t.append("c", "{}", 10 * s);
        assertEquals(2, t.lostIoError());
        assertEquals(List.of("can't open " + file("c") + "; dropping its messages for 1 s | open bug"), warnings(t));

        t.append("a", "{}", 20 * s); // a's retry is due
        sinks.failNextClose(file("a"), new IllegalStateException("close bug"));
        t.closeAll(20 * s);
        assertEquals("{}\n", sinks.content(file("a")));
        assertEquals(List.of("can't close " + file("a") + " | close bug"), warnings(t));
        assertEquals(0, sinks.openNow);
        assertEquals(0, budget.open());
        assertEquals(2, t.written());
    }

    // ---- closing ----

    @Test
    void closeAllWritesEverythingAndClosesEveryFile() {
        FileTable t = table(8192);
        t.append("a", "{\"n\":1}", 0);
        t.append("b", "{\"n\":1}", 0);
        t.closeAll(1);

        assertEquals(0, sinks.openNow);
        assertEquals(0, budget.open());
        assertEquals(0, t.dirtyCount());
        assertEquals(0, t.entryCount());
        assertEquals("{\"n\":1}\n", sinks.content(file("a")));

        t.append("a", "{\"n\":2}", 2); // reopens in append mode
        t.closeAll(3);
        assertEquals("{\"n\":1}\n{\"n\":2}\n", sinks.content(file("a")));
    }

    // ---- write-through mode ----

    @Test
    void writeThroughWritesEachRecordInOneCall() {
        FileTable t = table(8192);
        assertTrue(t.writeThrough("a", "{\"n\":1}", 0));
        assertTrue(t.writeThrough("a", "{\"n\":2}", 1));

        assertEquals(2, sinks.writes.size());
        assertEquals("{\"n\":1}\n", sinks.writes.get(0).text());
        assertEquals("{\"n\":2}\n", sinks.writes.get(1).text());
        assertEquals(2, t.written());
        assertEquals(0, t.dirtyCount());
    }

    @Test
    void writeThroughSplitsOnlyRecordsLargerThanTheBuffer() {
        FileTable t = table(64);
        String big = "{\"x\":\"" + "é😀".repeat(100) + "\"}";
        assertTrue(t.writeThrough("a", big, 0));

        assertEquals(big + "\n", sinks.content(file("a")));
        assertTrue(sinks.writes.size() > 1);
        assertTrue(sinks.writes.get(sinks.writes.size() - 1).text().endsWith("\n"));
    }

    @Test
    void writeThroughReportsDrops() {
        FileTable t = table(8192);
        assertFalse(t.writeThrough("a/b", "{}", 0));
        assertEquals(1, t.droppedInvalidId());

        assertTrue(t.writeThrough("a", "{}", 0));
        sinks.failNextWrite(file("a"), new IOException("disk full"));
        assertFalse(t.writeThrough("a", "{}", 1));
        assertFalse(t.writeThrough("a", "{}", 2), "backing off");
        assertEquals(2, t.lostIoError());
        assertEquals(1, t.written());
    }
}
