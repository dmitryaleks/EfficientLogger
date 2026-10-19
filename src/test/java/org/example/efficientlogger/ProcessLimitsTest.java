package org.example.efficientlogger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The limits every logger in a process shares, on loggers run by hand on a fake clock: the
 * latest build sets them for every logger, a build that leaves one out leaves it as it is, and
 * each logger still counts and reports its own drops.
 */
class ProcessLimitsTest {

    private static final long MS = 1_000_000L;
    private static final long GIB_KB = 1 << 20;

    private final Path dir = Paths.get("fake-shared-logs").toAbsolutePath().normalize();
    private final FakeSinks sinks = new FakeSinks(dir);
    private final long[] now = {0};
    private final long[] free = {10L << 20};
    private final List<String> warnings = new ArrayList<>();
    private final TestHooks hooks = new TestHooks(); // one set of hooks, so the loggers share its limits

    ProcessLimitsTest() {
        hooks.startThreads = false;
        hooks.sinks = sinks;
        hooks.nanoClock = () -> now[0];
        hooks.shardKey = 0;
        hooks.freeSpace = () -> free[0];
    }

    private EfficientLoggerBuilder builder(long maxFileSizeKb) {
        return EfficientLogger.builder(dir, "{id}.json", maxFileSizeKb)
                .warningHandler((message, cause) -> warnings.add(message));
    }

    private AsyncLogger build(EfficientLoggerBuilder b) {
        return (AsyncLogger) b.build(hooks);
    }

    private static String json(int n) {
        return "{\"n\":" + n + "}"; // 7 chars, 8 bytes with the separator
    }

    private String content(String id) {
        return sinks.content(dir.resolve(id + ".json"));
    }

    @Test
    void theRateCapIsOneBucketAndTheLatestBuildSetsIt() {
        AsyncLogger a = build(builder(GIB_KB).maxCharsPerSecond(20)); // a 7-char message is 0.35 s
        assertTrue(a.log("a", json(1)));
        assertTrue(a.log("a", json(2)));
        assertTrue(a.log("a", json(3))); // the output is now 1.05 s ahead
        AsyncLogger b = build(builder(GIB_KB)); // sets no rate: the process's stands
        assertFalse(b.log("b", json(1)), "one bucket for both loggers");
        assertEquals(1, b.stats().droppedRateLimit(), "b counts its own drop");
        assertEquals(0, a.stats().droppedRateLimit());

        build(builder(GIB_KB).maxCharsPerSecond(1_000_000)); // the latest build raises it for everyone
        assertFalse(a.log("a", json(4)), "the output already charged stands: still 1.05 s ahead");
        now[0] = 100 * MS;
        assertTrue(a.log("a", json(4)), "a, built first, is under the new rate");
        assertTrue(b.log("b", json(2)));
        assertTrue(b.log("b", json(3)), "which would have been refused at the old one");
    }

    @Test
    void maxFilesCountsTheFilesEveryLoggerCreates() {
        AsyncLogger a = build(builder(GIB_KB).maxFiles(2));
        assertTrue(a.log("a1", json(1)));
        assertTrue(a.log("a2", json(2)));
        a.worker(0).runOnce(); // two files created
        AsyncLogger b = build(builder(GIB_KB)); // sets no maxFiles: the process's cap of 2 stands
        assertTrue(b.log("b1", json(3)));
        b.worker(0).runOnce();
        assertEquals(1, b.stats().droppedInvalidId(), "the process is at its limit");
        assertNull(content("b1"));
        assertEquals(List.of("this process has reached its limit of 2 files (maxFiles, shared by all its loggers); dropping"
                + " messages for ids without a file yet (counted in stats().droppedInvalidId()) until it is raised"), warnings);

        build(builder(GIB_KB).maxFiles(3)); // the latest build raises it
        now[0] = 1_000 * MS; // the capped id is tried again after a second
        assertTrue(b.log("b1", json(4)));
        b.worker(0).runOnce();
        b.worker(0).beforeSleep(); // writes out
        assertEquals(json(4) + "\n", content("b1"));
        assertEquals(1, b.stats().droppedInvalidId(), "no more drops");
    }

    @Test
    void theFileCapIsOneValueForTheProcessAndAppliesToOpenFiles() {
        AsyncLogger a = build(builder(GIB_KB));
        assertTrue(a.log("a", record(1)));
        a.worker(0).runOnce(); // buffered under the 1 GiB cap
        build(builder(1)); // the latest build caps every file at 1 KiB
        for (int i = 2; i <= 201; i++) {
            assertTrue(a.log("a", record(i)));
        }
        a.worker(0).runOnce();
        LoggerStats s = a.stats();
        assertEquals(102, s.written(), "102 ten-byte records fit in 1 KiB; the 103rd found the file full");
        assertEquals(99, s.droppedFileFull(), "the rest were dropped, counted by a");
        assertEquals(1020, content("a").length());

        build(builder(GIB_KB)); // raised again
        now[0] = 1_000 * MS;
        assertTrue(a.log("a", record(202)));
        a.worker(0).runOnce();
        a.worker(0).beforeSleep();
        assertEquals(103, a.stats().written(), "the full file reopened because the cap is higher than when it filled");
        assertEquals(1030, content("a").length());
    }

    /** A record of exactly 10 bytes with its separator, whatever n is. */
    private static String record(int n) {
        return String.format("{\"n\":%03d}", n);
    }

    @Test
    void theDiskMarkIsSharedByLoggersOnOneVolume() {
        AsyncLogger a = build(builder(GIB_KB).minFreeDiskSpace(1 << 20, 2 << 20));
        AsyncLogger b = build(builder(GIB_KB)); // sets no mark: the process's stands, and it is on the same volume
        assertTrue(b.log("b", json(1)));

        free[0] = 512 << 10;
        now[0] = 1_000 * MS;
        a.worker(0).runOnce(); // a's worker takes the volume's reading
        assertFalse(b.log("b", json(2)), "b sheds on it");
        assertFalse(a.log("a", json(1)));
        b.worker(0).runOnce();
        assertEquals(1, b.stats().droppedDiskSpace());
        assertEquals(1, a.stats().droppedDiskSpace());
        assertEquals(1, warnings.size(), "b reports its own drop; a's worker hasn't run since its drop: " + warnings);
        assertTrue(warnings.get(0).startsWith("free disk space is 512.0 KiB, below minFreeDiskSpace=1.0 MiB"), warnings.get(0));

        free[0] = 3L << 20;
        now[0] = 2_000 * MS;
        b.worker(0).runOnce(); // either worker may take the reading
        assertTrue(a.log("a", json(2)), "and both resume");
        assertTrue(b.log("b", json(3)));
    }

    @Test
    void theSharedInstanceIsOnePerJvmAndTestsGetTheirOwn() {
        assertTrue(ProcessLimits.shared() == ProcessLimits.shared());
        assertTrue(hooks.limits != ProcessLimits.shared());
        assertTrue(new TestHooks().limits != hooks.limits);
    }
}
