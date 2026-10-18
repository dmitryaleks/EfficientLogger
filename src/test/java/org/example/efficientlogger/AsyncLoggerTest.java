package org.example.efficientlogger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Async mode with the workers run by hand on a fake clock: every step is deterministic. The same
 * code under real threads is tested in AsyncThreadsTest.
 */
class AsyncLoggerTest {

    private static final long MS = 1_000_000L;

    private final Path dir = Paths.get("fake-async-logs").toAbsolutePath().normalize();
    private final FakeSinks sinks = new FakeSinks(dir);
    private final long[] now = {0};
    private final List<String> warnings = new ArrayList<>();
    private int batchSize; // 0: the logger's own
    private long ringStartIndex;
    private int shardSeed; // 0: route by the bare hash, so worker assignment is predictable

    private EfficientLoggerBuilder builder() {
        return EfficientLogger.builder(dir, "{id}.json")
                .warningHandler((message, cause) -> warnings.add(cause == null ? message : message + " | " + cause.getMessage()));
    }

    /** An async logger whose workers run only when the test calls them. */
    private AsyncLogger manual(EfficientLoggerBuilder b) {
        TestHooks hooks = new TestHooks();
        hooks.startThreads = false;
        hooks.sinks = sinks;
        hooks.nanoClock = () -> now[0];
        hooks.batchSize = batchSize;
        hooks.ringStartIndex = ringStartIndex;
        hooks.shardSeed = shardSeed;
        return (AsyncLogger) b.build(hooks);
    }

    /** What the worker threads do after close: drain until nothing admitted is left, then finish. */
    private static void drainAndFinish(AsyncLogger logger) {
        while (!logger.worker(0).closedAndDrained()) {
            for (int i = 0; i < logger.workerCount(); i++) {
                logger.worker(i).runOnce();
            }
        }
        for (int i = 0; i < logger.workerCount(); i++) {
            logger.worker(i).finish();
        }
    }

    private String content(String id) {
        return sinks.content(dir.resolve(id + ".json"));
    }

    private static String json(int n) {
        return "{\"n\":" + n + "}";
    }

    // ---- queueing and writing ----

    @Test
    void logQueuesAndTheWorkerWritesOnceTheFlushIntervalHasPassed() {
        AsyncLogger logger = manual(builder());
        assertTrue(logger.log("a", json(1)));
        assertTrue(logger.log("a", json(2)));
        assertTrue(logger.log("b", json(3)));
        assertTrue(sinks.writes.isEmpty(), "log() only queues");
        LoggerStats s = logger.stats();
        assertEquals(3, s.accepted());
        assertEquals(3, s.queuedMessages());
        assertEquals(21, s.queuedChars());

        Worker w = logger.worker(0);
        assertEquals(3, w.runOnce());
        assertEquals(0, logger.stats().queuedMessages());
        assertTrue(sinks.writes.isEmpty(), "buffered until the oldest record has waited 100 ms");

        now[0] = 99 * MS;
        w.runOnce();
        assertTrue(sinks.writes.isEmpty());

        now[0] = 100 * MS;
        assertEquals(0, w.runOnce());
        assertEquals(json(1) + "\n" + json(2) + "\n", content("a"));
        assertEquals(json(3) + "\n", content("b"));
        assertEquals(3, logger.stats().written());
    }

    @Test
    void aZeroFlushIntervalWritesAfterEveryBatch() {
        AsyncLogger logger = manual(builder().flushIntervalMillis(0));
        logger.log("a", json(1));
        logger.worker(0).runOnce();
        assertEquals(json(1) + "\n", content("a"));
    }

    @Test
    void eachIdIsWrittenByOneWorker() {
        AsyncLogger logger = manual(builder().workerThreads(3).flushIntervalMillis(0));
        for (int i = 0; i < 30; i++) {
            logger.log("id" + i, json(i));
        }

        logger.worker(1).runOnce();
        for (int i = 0; i < 30; i++) {
            boolean ownedByWorker1 = Hashing.index(("id" + i).hashCode(), 3) == 1;
            if (ownedByWorker1) {
                assertEquals(json(i) + "\n", content("id" + i));
            } else {
                assertNull(content("id" + i), "id" + i + " belongs to another worker");
            }
        }

        logger.worker(0).runOnce();
        logger.worker(2).runOnce();
        for (int i = 0; i < 30; i++) {
            assertEquals(json(i) + "\n", content("id" + i));
        }
    }

    @Test
    void normalizeIdsMergesCaseAndUnicodeFormsIntoOneFileAndWorker() {
        // Three workers: case/form variants canonicalize to the same string, so they shard to one
        // worker and one file instead of racing across workers. (The content check is portable; a
        // check that "Foo.json" is absent is not, since Path equality is case-insensitive on Windows.)
        AsyncLogger logger = manual(builder().workerThreads(3).normalizeIds().flushIntervalMillis(0));
        assertTrue(logger.log("Foo", json(1)));
        assertTrue(logger.log("FOO", json(2)));
        assertTrue(logger.log("foo", json(3)));
        // café with é as one code point (NFC) and as e + combining accent (NFD) canonicalize together.
        assertTrue(logger.log("café", json(4)));
        assertTrue(logger.log("café", json(5)));

        for (int i = 0; i < logger.workerCount(); i++) {
            logger.worker(i).runOnce();
        }
        assertEquals(json(1) + "\n" + json(2) + "\n" + json(3) + "\n", content("foo"), "case variants share one file");
        assertEquals(json(4) + "\n" + json(5) + "\n", content("café"), "Unicode forms share one file");
    }

    @Test
    void theShardSeedDecidesWhichWorkerAnIdGoesTo() {
        // The per-logger seed, not the bare hash, picks the worker, so an attacker who controls IDs
        // can't craft them to all pile on one worker without knowing the seed.
        for (int seed : new int[] {0, 0x51ED2A17, -987654321}) {
            shardSeed = seed;
            AsyncLogger logger = manual(builder().workerThreads(4));
            assertTrue(logger.log("tenant", json(1)));
            int expected = Hashing.index("tenant".hashCode() ^ seed, 4);
            for (int w = 0; w < logger.workerCount(); w++) {
                long queued = logger.worker(w).ring.tail(); // ringStartIndex is 0
                assertEquals(w == expected ? 1 : 0, queued, "seed " + seed + ", worker " + w);
            }
        }
    }

    @Test
    void batchesAreAQuarterOfTheQueueUpTo256() {
        AsyncLogger logger = manual(builder().maxQueuedMessages(1000));
        for (int i = 0; i < 600; i++) {
            logger.log("a", json(i));
        }
        Worker w = logger.worker(0);
        assertEquals(250, w.runOnce());
        assertEquals(250, w.runOnce());
        assertEquals(100, w.runOnce());
        assertEquals(0, w.runOnce());
    }

    @Test
    void positionsPastIntMaxValueWork() {
        ringStartIndex = (1L << 31) - 3;
        AsyncLogger logger = manual(builder().maxQueuedMessages(8).flushIntervalMillis(0));
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            assertTrue(logger.log("a", json(i)));
            expected.append(json(i)).append('\n');
            logger.worker(0).runOnce();
        }
        assertEquals(expected.toString(), content("a"));
        assertEquals(20, logger.stats().accepted());
    }

    @Test
    void nullArgumentsThrowBeforeAnythingChanges() {
        AsyncLogger logger = manual(builder());
        assertThrows(NullPointerException.class, () -> logger.log(null, "{}"));
        assertThrows(NullPointerException.class, () -> logger.log("a", null));
        LoggerStats s = logger.stats();
        assertEquals(0, s.accepted());
        assertEquals(0, s.queuedMessages());
        assertEquals(0, logger.droppedCount());
    }

    // ---- the queue limits ----

    @Test
    void dropsWhenFullAndWarnsOncePerEpisode() {
        AsyncLogger logger = manual(builder().maxQueuedMessages(8)); // batches of 2; half empty at 4
        Worker w = logger.worker(0);
        assertEquals(8, logCounting(logger, 10));
        assertEquals(2, logger.droppedCount());
        assertEquals(2, logger.stats().droppedQueueFull());
        assertTrue(warnings.isEmpty(), "producers never warn");

        w.runOnce(); // 6 queued
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).startsWith("queue full (maxQueuedMessages=8, maxQueuedChars=16777216)"), warnings.get(0));

        w.runOnce(); // 4 queued: half empty
        assertEquals("queue recovered; dropped 2 messages (2 in total)", warnings.get(1));

        // A second episode, after the rate-limit interval so it is reported again.
        now[0] = 20_000 * MS;
        assertEquals(4, logCounting(logger, 6));
        w.runOnce();
        assertEquals(3, warnings.size(), warnings.toString());
        assertTrue(warnings.get(2).startsWith("queue full"), warnings.get(2));
        w.runOnce();
        assertEquals("queue recovered; dropped 2 messages (4 in total)", warnings.get(3));

        while (w.runOnce() > 0) {
            // drain the rest
        }
        assertEquals(4, warnings.size(), warnings.toString());
    }

    @Test
    void overflowWarningsAreRateLimited() {
        batchSize = 8;
        AsyncLogger logger = manual(builder().maxQueuedMessages(8)); // half empty at 4
        Worker w = logger.worker(0);

        assertEquals(8, logCounting(logger, 12)); // episode 1 at t=0: 4 dropped
        w.runOnce(); // drains 8; the episode's start is reported
        w.runOnce(); // queue empty: its end is reported
        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).startsWith("queue full"), warnings.get(0));
        assertEquals("queue recovered; dropped 4 messages (4 in total)", warnings.get(1));

        assertEquals(8, logCounting(logger, 12)); // episode 2, still at t=0: 4 more dropped
        w.runOnce();
        w.runOnce();
        assertEquals(2, warnings.size(), "a second episode within the interval is silent: " + warnings);

        now[0] = 11_000 * MS; // past the interval
        assertEquals(8, logCounting(logger, 12)); // 4 more dropped, 12 in all
        w.runOnce();
        w.runOnce();
        assertEquals(3, warnings.size(), warnings.toString());
        assertEquals("queue recovered; dropped 8 messages (12 in total)", warnings.get(2),
                "the folded drops surface once reporting resumes");
    }

    @Test
    void dropsWhileStillAboveHalfAddNoWarnings() {
        batchSize = 2;
        AsyncLogger logger = manual(builder().maxQueuedMessages(64)); // half empty at 32
        Worker w = logger.worker(0);
        assertEquals(64, logCounting(logger, 70));
        w.runOnce(); // 62 queued: the episode starts
        assertEquals(1, warnings.size(), warnings.toString());

        assertEquals(2, logCounting(logger, 5)); // 3 more dropped, 9 in all
        while (logger.stats().queuedMessages() > 34) {
            w.runOnce();
        }
        assertEquals(1, warnings.size(), "no warnings while above half: " + warnings);

        w.runOnce(); // 32 queued
        assertEquals("queue recovered; dropped 9 messages (9 in total)", warnings.get(1));
    }

    @Test
    void aMessageLongerThanMaxQueuedCharsIsDroppedWithoutAFullQueueWarning() {
        AsyncLogger logger = manual(builder().maxQueuedChars(100));
        assertFalse(logger.log("a", "x".repeat(101)));
        assertFalse(logger.log("a", "x".repeat(500)));
        assertTrue(logger.log("a", "x".repeat(100)));
        LoggerStats s = logger.stats();
        assertEquals(2, s.droppedTooLarge());
        assertEquals(0, s.droppedQueueFull(), "a too-large message is not a full queue");
        assertEquals(2, logger.droppedCount());

        logger.worker(0).runOnce();
        // Its own warning, once, with no bogus "queue full"/"recovered" pair.
        assertEquals(List.of("dropping messages longer than maxQueuedChars=100 chars"
                + " (further ones are only counted, in stats().droppedTooLarge())"), warnings);
    }

    @Test
    void theCharLimitCoversTheWholeQueue() {
        AsyncLogger logger = manual(builder().maxQueuedChars(100).workerThreads(2));
        String thirty = "x".repeat(30);
        assertTrue(logger.log("a", thirty));
        assertTrue(logger.log("b", thirty));
        assertTrue(logger.log("c", thirty));
        assertFalse(logger.log("d", thirty));
        assertTrue(logger.log("d", "x".repeat(10)));
        assertEquals(100, logger.stats().queuedChars());
    }

    // ---- closing ----

    @Test
    void closeStopsAcceptingAndTheWorkerDrainsEverything() {
        AsyncLogger logger = manual(builder());
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            logger.log("a", json(i));
            expected.append(json(i)).append('\n');
        }

        assertFalse(logger.close(Duration.ZERO), "the workers haven't finished yet");
        assertFalse(logger.log("a", json(99)));
        assertEquals(1, logger.stats().rejectedClosed());
        assertEquals(0, logger.droppedCount(), "rejected after close isn't a drop");

        drainAndFinish(logger);

        assertEquals(expected.toString(), content("a"));
        assertEquals(0, sinks.openNow);
        assertTrue(logger.worker(0).terminated());
        assertTrue(logger.close(Duration.ZERO), "finished now");
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void afterCloseEachWorkerWaitsUntilAllAreDrained() {
        AsyncLogger logger = manual(builder().workerThreads(3));
        for (int i = 0; i < 30; i++) {
            logger.log("id" + i, json(i));
        }
        logger.close(Duration.ZERO);

        while (logger.worker(0).runOnce() > 0) {
            // drain worker 0's own ring
        }
        assertFalse(logger.worker(0).closedAndDrained(), "the other workers still hold messages");

        drainAndFinish(logger);
        for (int i = 0; i < 30; i++) {
            assertEquals(json(i) + "\n", content("id" + i));
        }
    }

    @Test
    void dropsNotYetReportedAtCloseAreReported() {
        batchSize = 8;
        AsyncLogger logger = manual(builder().maxQueuedMessages(8));
        assertEquals(8, logCounting(logger, 10));
        logger.close(Duration.ZERO);

        drainAndFinish(logger); // one batch takes all 8, so the episode's recovery check never runs

        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).startsWith("queue full"), warnings.get(0));
        assertEquals("dropped 2 messages before close (2 in total)", warnings.get(1));
    }

    // ---- what gets written ----

    @Test
    void aMessageCantSpanLines() {
        AsyncLogger logger = manual(builder().flushIntervalMillis(0));
        logger.log("a", "{\"note\":\"hi\"}\n{\"forged\":true}");
        logger.log("a", "{\r\n  \"n\": 1\r\n}");
        logger.worker(0).runOnce();
        assertEquals("{\"note\":\"hi\"} {\"forged\":true}\n{    \"n\": 1  }\n", content("a"));
    }

    @Test
    void rejectsIdsTooLongForAFileNameAtOnce() {
        AsyncLogger logger = manual(builder()); // "{id}.json" leaves room for 250 chars
        String longest = "a".repeat(250);
        assertTrue(logger.log(longest, json(1)));
        assertFalse(logger.log("a".repeat(251), json(2)));
        assertFalse(logger.log("b".repeat(10_000_000), json(3)));

        LoggerStats s = logger.stats();
        assertEquals(1, s.queuedMessages(), "the long ids never reach the queue");
        assertEquals(3, s.accepted());
        assertEquals(2, s.droppedInvalidId());
        assertTrue(warnings.isEmpty(), "the caller doesn't report it; a worker does");

        logger.worker(0).runOnce();
        assertEquals(List.of("dropping messages whose id is longer than 250 chars, the most a file name has room"
                + " for (further ones are only counted, in stats().droppedInvalidId())"), warnings);

        logger.close(Duration.ZERO);
        drainAndFinish(logger);
        s = logger.stats();
        assertEquals(s.accepted(), s.written() + s.droppedInvalidId() + s.lostIoError());
        assertEquals(json(1) + "\n", content(longest));
        assertEquals(1, warnings.size(), "reported once");
    }

    // ---- errors ----

    @Test
    void statsAddUpOnceDrained() {
        AsyncLogger logger = manual(builder().flushIntervalMillis(0));
        sinks.failNextWrite(dir.resolve("b.json"), new IOException("disk full"));
        assertTrue(logger.log("a", json(1)));
        assertTrue(logger.log("b", json(2)));
        assertTrue(logger.log("bad/id", json(3)), "accepted now, dropped by the worker");
        assertTrue(logger.log("a", json(4)));
        logger.close(Duration.ZERO);
        drainAndFinish(logger);

        LoggerStats s = logger.stats();
        assertEquals(4, s.accepted());
        assertEquals(2, s.written());
        assertEquals(1, s.droppedInvalidId());
        assertEquals(1, s.lostIoError());
        assertEquals(s.accepted(), s.written() + s.droppedInvalidId() + s.lostIoError());
        assertEquals(0, s.queuedMessages());
        assertEquals(0, s.openFiles());
    }

    @Test
    void anUnexpectedErrorDropsOnlyThatMessage() {
        // A 64-byte buffer holds one of these records, so the second one forces a write, which fails.
        // (Exceptions from a sink are handled as I/O errors; an Error gets through to the worker.)
        AsyncLogger logger = manual(builder().fileBufferBytes(64));
        sinks.failNextWrite(dir.resolve("a.json"), new OutOfMemoryError("test"));
        logger.log("a", record(1));
        logger.log("a", record(2));
        logger.log("a", record(3));
        logger.worker(0).runOnce();

        assertEquals(List.of("unexpected error writing a message for id \"a\"; it was dropped | test"), warnings);

        logger.close(Duration.ZERO);
        drainAndFinish(logger);
        assertEquals(record(1) + "\n" + record(3) + "\n", content("a"));
        assertEquals(2, logger.stats().written());
        assertEquals(1, logger.stats().lostIoError());
    }

    private static String record(int n) {
        return "{\"n\":" + n + ",\"pad\":\"" + "x".repeat(36) + "\"}"; // 50 chars
    }

    /** Logs {@code n} messages to "a" and returns how many were accepted. */
    private static int logCounting(AsyncLogger logger, int n) {
        int accepted = 0;
        for (int i = 0; i < n; i++) {
            if (logger.log("a", json(i))) {
                accepted++;
            }
        }
        return accepted;
    }
}
