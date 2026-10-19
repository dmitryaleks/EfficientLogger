package org.example.efficientlogger;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private FileCreationBudget fileBudget = new FileCreationBudget(FileCreationBudget.UNLIMITED);
    private long maxFileBytes = Long.MAX_VALUE;

    private FileTable table(int bufferBytes) {
        return table(bufferBytes, "\n", 1024 + FileTable.EXTRA_ENTRIES);
    }

    private FileTable table(int bufferBytes, String separator, int maxEntries) {
        return new FileTable(FileNamePattern.parse(DIR, "{id}.json"), separator.getBytes(US_ASCII),
                bufferBytes, maxEntries, maxFileBytes, sinks, budget, fileBudget, warnings);
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
    void escapesSurviveBeingWrittenInPieces() {
        // Far larger than the 64-byte buffer, with an escape (6 bytes) or a space at every 4th char.
        String message = ("ab" + (char) 1 + "\n").repeat(100);
        String expected = "ab\\u0001 ".repeat(100) + "\n";
        FileTable buffered = table(64);
        buffered.append("a", message, 0);
        buffered.flushDirty(1);
        assertEquals(expected, sinks.content(file("a")));

        FileTable writeThrough = table(64);
        assertTrue(writeThrough.writeThrough("b", message, 0));
        assertEquals(expected, sinks.content(file("b")));
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

    // ---- max files ----

    @Test
    void capsNewFilesAtMaxFiles() {
        fileBudget = new FileCreationBudget(2);
        FileTable t = table(8192);
        t.append("a", "{}", 0);
        t.append("b", "{}", 0);
        t.append("c", "{}", 0); // over the cap: no file created
        t.flushDirty(0);

        assertEquals("{}\n", sinks.content(file("a")));
        assertEquals("{}\n", sinks.content(file("b")));
        assertNull(sinks.content(file("c")), "no file for the capped id");
        assertEquals(2, t.written());
        assertEquals(1, t.droppedInvalidId(), "the capped message is counted");
        List<String> w = warnings(t);
        assertEquals(1, w.size(), w.toString());
        assertTrue(w.get(0).contains("maxFiles"), w.get(0));

        t.append("c", "{}", 0); // still capped
        t.append("d", "{}", 0); // another new id, also capped
        assertEquals(3, t.droppedInvalidId());
        assertTrue(warnings(t).isEmpty(), "warned only once");
    }

    /**
     * Every failed open of a new file used to keep its maxFiles unit, and the retry a second later
     * took another, so an outage could cap a long-running logger for good (review R3).
     */
    @Test
    void aFailedOpenGivesItsMaxFilesReservationBack() {
        fileBudget = new FileCreationBudget(2);
        FileTable t = table(8192);
        sinks.failNextOpen(file("a"), new IOException("denied"));
        t.append("a", "{}", 0); // reserves a unit, then the open fails: the unit must come back
        t.append("b", "{}", 0); // the first unit
        assertEquals(1, t.lostIoError());

        t.append("a", "{}", 1000 * MS); // the retry creates a: the second and last unit
        t.append("c", "{}", 1000 * MS); // over the cap
        t.flushDirty(1000 * MS);

        assertEquals("{}\n", sinks.content(file("a")));
        assertEquals("{}\n", sinks.content(file("b")));
        assertNull(sinks.content(file("c")));
        assertEquals(2, t.written());
        assertEquals(1, t.droppedInvalidId(), "only c is capped");
        List<String> w = warnings(t);
        assertEquals(2, w.size(), w.toString());
        assertTrue(w.get(0).startsWith("can't open " + file("a")), w.get(0));
        assertTrue(w.get(1).contains("maxFiles"), w.get(1));
    }

    /** An existing file that happens to be empty is not a new file (review R3). */
    @Test
    void anExistingEmptyFileDoesNotCountAgainstMaxFiles() {
        sinks.preset(file("a"), ""); // left by an earlier run that wrote nothing
        fileBudget = new FileCreationBudget(1);
        FileTable t = table(8192);
        t.append("a", "{}", 0); // reopens the empty file: no unit used
        t.append("b", "{}", 0); // the one new file
        t.append("c", "{}", 0); // capped
        t.flushDirty(0);

        assertEquals("{}\n", sinks.content(file("a")));
        assertEquals("{}\n", sinks.content(file("b")));
        assertNull(sinks.content(file("c")));
        assertEquals(1, t.droppedInvalidId());
    }

    @Test
    void reopeningAnExistingFileDoesNotCountAgainstMaxFiles() {
        sinks.preset(file("a"), "{\"old\":1}\n"); // a already exists on disk
        fileBudget = new FileCreationBudget(1); // room for just one new file
        FileTable t = table(8192);

        t.append("a", "{\"n\":2}", 0); // reopens the existing file: doesn't use the budget
        t.append("b", "{}", 0); // one new file: uses the budget
        t.append("c", "{}", 0); // over the cap: capped
        t.flushDirty(0);

        assertEquals("{\"old\":1}\n{\"n\":2}\n", sinks.content(file("a")));
        assertEquals("{}\n", sinks.content(file("b")));
        assertNull(sinks.content(file("c")));
        assertEquals(1, t.droppedInvalidId());
    }

    // ---- I/O errors ----

    @Test
    void refusesAFileThatIsNotRegular() {
        // A symlink, FIFO or device left at the path, e.g. by another user who can write the
        // directory, would redirect the writes or block the thread. It is refused like any I/O error.
        FileTable t = table(8192);
        sinks.presetNonRegular(file("a"));

        t.append("a", "{\"n\":1}", 0);
        assertEquals(1, t.lostIoError());
        assertEquals(0, t.written());
        assertEquals(0, budget.open(), "no handle is held for a refused file");
        assertNull(sinks.content(file("a")), "nothing is written to the path");
        List<String> w = warnings(t);
        assertEquals(1, w.size(), w.toString());
        assertTrue(w.get(0).contains("not a regular file"), w.get(0));

        t.append("a", "{\"n\":2}", 999 * MS); // still backing off
        t.append("a", "{\"n\":3}", 1000 * MS); // retry due, but still not a regular file
        assertEquals(3, t.lostIoError());
        assertEquals(0, t.written());
        assertNull(sinks.content(file("a")));
        assertTrue(warnings(t).isEmpty(), "further refusals are rate-limited");
    }

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

    // ---- maxFileSizeKb ----

    @Test
    void stopsWritingAFileAtItsCapAndDropsTheRestUntilItIsTruncated() {
        maxFileBytes = 20; // two 8-byte records fit, a third wouldn't
        FileTable t = table(8192);
        t.append("a", "{\"n\":1}", 0);
        t.append("a", "{\"n\":2}", 0);
        t.append("a", "{\"n\":3}", 0); // would make 24 bytes
        assertEquals("{\"n\":1}\n{\"n\":2}\n", sinks.content(file("a")), "what fits is written out when the file fills");
        assertEquals(2, t.written());
        assertEquals(1, t.droppedFileFull());
        assertEquals(0, t.openCount(), "the full file is closed");
        assertEquals(0, t.dirtyCount());
        assertEquals(List.of("file " + file("a") + " is at maxFileSizeKb (16 bytes); dropping its messages (counted in"
                + " stats().droppedFileFull()) until something truncates it, checked once a second"), warnings(t));

        t.append("a", "{\"n\":4}", 500 * MS); // within the second: dropped without looking at the file
        assertEquals(2, t.droppedFileFull());
        t.append("a", "{\"n\":5}", 1_000 * MS); // a second later: still 16 bytes, so still full, and no second warning
        assertEquals(3, t.droppedFileFull());
        assertEquals(0, t.openCount());
        assertTrue(warnings(t).isEmpty());

        sinks.truncate(file("a")); // logrotate's copytruncate
        t.append("a", "{\"n\":6}", 1_500 * MS); // not checked yet
        assertEquals(4, t.droppedFileFull());
        t.append("a", "{\"n\":7}", 2_000 * MS); // checked: the file has shrunk, so it is reopened and written to
        t.flushDirty(2_000 * MS);
        assertEquals("{\"n\":7}\n", sinks.content(file("a")));
        assertEquals(3, t.written());
        assertEquals(1, t.openCount());
        assertEquals(4, t.droppedFileFull());
        assertTrue(warnings(t).isEmpty());
    }

    @Test
    void aFileAlreadyAtTheCapIsNotEvenOpened() {
        maxFileBytes = 10;
        sinks.preset(file("a"), "0123456789\n"); // 11 bytes, from an earlier run
        FileTable t = table(8192);
        t.append("a", "{}", 0);
        assertEquals(1, t.droppedFileFull());
        assertEquals(0, t.openCount());
        assertEquals(0, sinks.opens);
        assertEquals(1, warnings(t).size());
        assertEquals("0123456789\n", sinks.content(file("a")));
        assertEquals(0, budget.open(), "the open-file slot was given back");
    }

    @Test
    void aRecordLargerThanTheBufferIsMeasuredBeforeAnyOfItIsWritten() {
        maxFileBytes = 100;
        FileTable t = table(64); // records over 62 bytes are written in pieces
        t.append("a", "x".repeat(150), 0); // 151 bytes would take three writes; none happens
        assertTrue(sinks.writes.isEmpty());
        assertEquals(1, t.droppedFileFull());
        assertEquals(1, warnings(t).size());
        assertFalse(t.writeThrough("b", "y".repeat(150), 0));
        assertTrue(sinks.writes.isEmpty());
        assertEquals(2, t.droppedFileFull());

        // A record that fits is written, in pieces, and counts towards the cap exactly.
        assertTrue(t.writeThrough("c", "z".repeat(99), 0));
        assertEquals("z".repeat(99) + "\n", sinks.content(file("c")));
        assertFalse(t.writeThrough("c", "{}", 0), "the file is at the cap to the byte");
        assertEquals(3, t.droppedFileFull());
        assertEquals(1, t.written());
        assertEquals(0, t.lostIoError());
    }

    @Test
    void aFileLeftLargerThanTheCapStaysFullQuietlyUntilItIsBelowIt() {
        maxFileBytes = 10;
        sinks.preset(file("a"), "x".repeat(30)); // from a run with a bigger cap
        FileTable t = table(8192);
        t.append("a", "{}", 0);
        assertEquals(1, warnings(t).size());

        sinks.preset(file("a"), "x".repeat(20)); // truncated, but not below the cap
        t.append("a", "{}", 1_000 * MS);
        assertEquals(2, t.droppedFileFull());
        assertEquals(0, sinks.opens, "not reopened");
        assertTrue(warnings(t).isEmpty(), "and not warned about again (review 20, C8)");

        sinks.preset(file("a"), "x".repeat(5)); // now below the cap
        t.append("a", "{}", 2_000 * MS);
        t.flushDirty(2_000 * MS);
        assertEquals("xxxxx\n{}\n", sinks.content(file("a")), "reopened, its torn line finished, and written to");
        assertEquals(1, t.written());
        assertEquals(2, t.droppedFileFull());
    }

    @Test
    void aFullFileWhoseSizeCantBeReadIsTreatedAsAnIoError() {
        maxFileBytes = 10;
        FileTable t = table(8192);
        t.append("a", "{\"n\":12345}", 0); // 11 bytes and a separator: never fits
        assertEquals(1, t.droppedFileFull());
        assertEquals(1, warnings(t).size());

        sinks.presetNonRegular(file("a")); // swapped for a symlink meanwhile
        t.append("a", "{}", 1_000 * MS);
        assertEquals(1, t.droppedFileFull(), "counted as an I/O loss instead");
        assertEquals(1, t.lostIoError());
        List<String> w = warnings(t);
        assertEquals(1, w.size(), w::toString);
        assertTrue(w.get(0).startsWith("can't check the size of " + file("a") + "; dropping its messages for 1 s | refusing to use"), w.get(0));
    }

    @Test
    void fullFilesAreEvictedAndClosedLikeAnyEntry() {
        maxFileBytes = 10;
        FileTable t = table(8192, "\n", 2); // room for two entries
        t.append("a", "{\"n\":12345}", 0); // full at once
        t.append("b", "{}", 0);
        t.append("c", "{}", 0); // evicts a, the least recently used
        assertEquals(2, t.entryCount());
        assertEquals(2, t.openCount());
        t.append("d", "{\"n\":12345}", 0); // evicts b (writing it out); d is full at once
        assertEquals(1, t.openCount());
        t.closeAll(0);
        assertEquals(0, t.openCount());
        assertEquals(0, t.entryCount());
        assertEquals(2, t.droppedFileFull());
        assertEquals(2, t.written());
        assertEquals(0, budget.open(), "every open-file slot given back");
    }

    @Test
    void theSeparatorCountsTowardsTheCap() {
        maxFileBytes = 8;
        FileTable t = table(8192, "\r\n", 1024 + FileTable.EXTRA_ENTRIES);
        t.append("a", "{}", 0); // 4 bytes
        t.append("a", "{}", 0); // 8: fits exactly
        t.append("a", "{}", 0); // 12: full
        assertEquals("{}\r\n{}\r\n", sinks.content(file("a")));
        assertEquals(1, t.droppedFileFull());
        assertEquals(2, t.written());
    }

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
