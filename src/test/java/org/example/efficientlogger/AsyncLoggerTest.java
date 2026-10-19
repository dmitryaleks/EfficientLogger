package org.example.efficientlogger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private long shardKey; // fixed (0 by default), so worker assignment is predictable
    private DiskWatch.Probe freeSpace; // for minFreeDiskSpace tests

    private EfficientLoggerBuilder builder() {
        return EfficientLogger.builder(dir, "{id}.json", 1 << 20)
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
        hooks.shardKey = shardKey;
        hooks.freeSpace = freeSpace;
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
            boolean ownedByWorker1 = Hashing.bucket("id" + i, 0, 3) == 1;
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
    void theShardKeyDecidesWhichWorkerAnIdGoesTo() {
        // A per-logger key, not the bare hash, picks the worker, so an attacker who controls IDs
        // can't craft them to all pile on one worker without knowing the key.
        Set<Integer> workers = new HashSet<>();
        for (long key : new long[] {0, 0x51ED2A17L, -987654321L, Long.MAX_VALUE}) {
            shardKey = key;
            AsyncLogger logger = manual(builder().workerThreads(4));
            assertTrue(logger.log("tenant", json(1)));
            int expected = Hashing.bucket("tenant", key, 4);
            workers.add(expected);
            for (int w = 0; w < logger.workerCount(); w++) {
                long queued = logger.worker(w).ring.tail(); // ringStartIndex is 0
                assertEquals(w == expected ? 1 : 0, queued, "key " + key + ", worker " + w);
            }
        }
        assertTrue(workers.size() > 1, "the key made no difference: " + workers);
    }

    /**
     * String.hashCode() collisions are easy to make in bulk and are the same whatever is mixed in
     * afterwards, so routing by that hash let a caller pin one worker for every seed (audit F18,
     * review R5).
     */
    @Test
    void idsWithEqualHashCodesAreSpreadOverWorkers() {
        AsyncLogger logger = manual(builder().workerThreads(4));
        List<String> ids = HashingTest.collidingIds(5); // 32 ids, one hash code
        for (String id : ids) {
            assertTrue(logger.log(id, json(1)));
        }
        int busy = 0;
        for (int w = 0; w < logger.workerCount(); w++) {
            if (logger.worker(w).ring.tail() > 0) {
                busy++;
            }
        }
        assertTrue(busy >= 3, "32 ids with one hash code reached only " + busy + " of 4 workers");
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

    // ---- minFreeDiskSpace and maxCharsPerSecond ----

    @Test
    void shedsMessagesWhileDiskSpaceIsLowWithHysteresis() {
        long[] free = {10L << 20};
        freeSpace = () -> free[0];
        AsyncLogger logger = manual(builder().minFreeDiskSpace(1 << 20, 2 << 20));
        Worker w = logger.worker(0);
        assertTrue(logger.log("a", json(1)));

        free[0] = 512 << 10; // the volume fills up; the next reading, a second after the last, sees it
        assertTrue(logger.log("a", json(2)), "not read yet");
        now[0] = 1_000 * MS;
        w.runOnce();
        assertFalse(logger.log("a", json(3)));
        assertFalse(logger.log("a", json(4)));
        LoggerStats s = logger.stats();
        assertEquals(2, s.droppedDiskSpace());
        assertEquals(2, logger.droppedCount());
        assertEquals(2, s.accepted());
        assertTrue(warnings.isEmpty(), "producers never warn; the worker does on its next pass");
        w.runOnce();
        assertEquals(List.of("free disk space is 512.0 KiB, below minFreeDiskSpace=1.0 MiB; dropping messages until it is back"
                + " above 2.0 MiB"), warnings);

        free[0] = 1536 << 10; // above the floor, below the resume mark
        now[0] = 2_000 * MS;
        w.runOnce();
        assertFalse(logger.log("a", json(5)), "hysteresis: still shedding");
        free[0] = 3L << 20;
        now[0] = 3_000 * MS;
        w.runOnce();
        assertTrue(logger.log("a", json(6)));
        assertEquals(2, warnings.size(), warnings::toString);
        assertEquals("free disk space is back to 3.0 MiB; dropped 3 messages (3 in total)", warnings.get(1));

        logger.close(Duration.ZERO);
        drainAndFinish(logger);
        assertEquals(json(1) + "\n" + json(2) + "\n" + json(6) + "\n", content("a"));
        s = logger.stats();
        assertEquals(3, s.droppedDiskSpace());
        assertEquals(3, s.accepted());
        assertEquals(s.accepted(), s.written() + s.droppedInvalidId() + s.droppedFileFull() + s.lostIoError() + s.abandoned());
        assertEquals(2, warnings.size(), "nothing left to report at close");
    }

    @Test
    void capsTheOutputRateAndDropsTheExcess() {
        AsyncLogger logger = manual(builder().maxCharsPerSecond(20)); // a 7-char message is 0.35 s of output
        Worker w = logger.worker(0);
        assertTrue(logger.log("a", json(1))); // the output is now 0.35 s ahead
        assertTrue(logger.log("a", json(2))); // 0.7 s
        assertTrue(logger.log("a", json(3))); // judged at 0.7 s, so accepted; now 1.05 s ahead
        assertFalse(logger.log("a", json(4)), "1.05 s ahead: over the burst of one second");
        assertFalse(logger.log("a", json(5)));
        LoggerStats s = logger.stats();
        assertEquals(2, s.droppedRateLimit());
        assertEquals(2, logger.droppedCount());
        assertEquals(3, s.accepted());
        assertTrue(warnings.isEmpty(), "producers never warn");
        w.runOnce();
        assertEquals(List.of("output rate limit reached (maxCharsPerSecond=20); dropping messages until the rate falls"), warnings);

        now[0] = 100 * MS;
        assertTrue(logger.log("a", json(6)), "0.1 s later the output is 0.95 s ahead: within the burst again");
        assertFalse(logger.log("a", json(7)));
        now[0] = 1_200 * MS; // more than a second with nothing dropped
        w.runOnce();
        assertEquals("output rate is back under the limit; dropped 3 messages (3 in total)", warnings.get(1));

        // Over a long stretch, calls at ten a second are accepted at the cap: 20 chars/s is one message per 0.35 s.
        now[0] = 10_000 * MS;
        int accepted = 0;
        for (int i = 0; i < 100; i++) {
            now[0] += 100 * MS;
            if (logger.log("b", json(i))) {
                accepted++;
            }
        }
        assertTrue(accepted >= 25 && accepted <= 31, "10 s at the cap is 28 messages, give or take the burst: " + accepted);

        logger.close(Duration.ZERO);
        drainAndFinish(logger);
        s = logger.stats();
        assertEquals(4 + accepted, s.written());
        assertEquals(s.accepted(), s.written());
        assertEquals(103 - accepted, s.droppedRateLimit());
    }

    // ---- flush ----

    /**
     * Serving a flush request used to spin until the ring reached the request's target, so one
     * slot claimed but never published (a log() cut off between the claim and the publish) kept
     * the worker inside it for good (review R1). It must give up and come back to the request.
     */
    @Test
    void aFlushRequestBehindAnUnpublishedSlotDoesNotStopTheWorkerLoop() throws InterruptedException {
        AsyncLogger logger = manual(builder().flushIntervalMillis(0));
        Worker w = logger.worker(0);
        assertTrue(logger.log("a", json(1)));
        assertTrue(logger.admission().tryAcquire(json(2).length()) > 0); // a log() cut off: admitted, claimed...
        long stuck = w.ring.claimWithoutPublishingForTest(); // ...but never published
        assertTrue(logger.log("a", json(3)));
        long ticket = w.requestFlush();

        assertEquals(1, w.runOnce(), "takes the message before the slot and stops at it");
        assertEquals(json(1) + "\n", content("a"), "which is still written out");
        assertEquals(0, w.runOnce(), "returns rather than spinning on the slot");
        assertFalse(w.awaitFlush(ticket, System.nanoTime()), "the flush is still pending");
        assertEquals(2, logger.stats().queuedMessages());

        w.ring.publishForTest(stuck, "a", json(2)); // the producer was only slow after all
        assertEquals(2, w.runOnce(), "the flush is then served");
        assertTrue(w.awaitFlush(ticket, System.nanoTime()));
        assertEquals(json(1) + "\n" + json(2) + "\n" + json(3) + "\n", content("a"));
        assertEquals(0, logger.stats().queuedMessages());
    }

    /**
     * A flush over a backlog is served one batch per step, so on a shared thread the other workers
     * get their turns in between; it used to drain the whole ring in one go (review 19, T4).
     */
    @Test
    void aFlushIsServedOneBatchPerStep() throws InterruptedException {
        batchSize = 2;
        AsyncLogger logger = manual(builder().flushIntervalMillis(60_000));
        Worker w = logger.worker(0);
        for (int i = 1; i <= 6; i++) {
            assertTrue(logger.log("a", json(i)));
        }
        long ticket = w.requestFlush();

        assertEquals(2, w.runOnce());
        assertFalse(w.awaitFlush(ticket, System.nanoTime()), "two of six taken: the flush is still pending");
        assertEquals(4, logger.stats().queuedMessages());
        assertEquals(0, logger.stats().written(), "nothing is written out until the flush completes");
        assertEquals(2, w.runOnce());
        assertFalse(w.awaitFlush(ticket, System.nanoTime()));
        assertEquals(2, w.runOnce());
        assertTrue(w.awaitFlush(ticket, System.nanoTime()), "all six taken: written out and done");
        assertEquals(json(1) + "\n" + json(2) + "\n" + json(3) + "\n" + json(4) + "\n" + json(5) + "\n" + json(6) + "\n",
                content("a"));
        assertEquals(0, logger.stats().queuedMessages());
        assertEquals(6, logger.stats().written());
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

    /**
     * Normalizing copies the ID, so an ID that can't possibly normalize to fit must be rejected
     * before that (review R6): otherwise a 20-million-char ID costs 80 MB of garbage per call.
     */
    @Test
    void normalizeIdsRejectsAHugeIdWithoutCopyingIt() {
        com.sun.management.ThreadMXBean mx = allocationCounter();
        AsyncLogger logger = manual(builder().normalizeIds());
        assertFalse(logger.log("a".repeat(10_000), json(1)), "warms up the rejection path"); // 10,000 > 8 x 250
        String huge = "x".repeat(20_000_000);

        long before = mx.getCurrentThreadAllocatedBytes();
        assertFalse(logger.log(huge, json(2)));
        long allocated = mx.getCurrentThreadAllocatedBytes() - before;

        assertTrue(allocated < 1_000_000, "rejecting the huge id allocated " + allocated + " bytes: it was copied first");
        assertEquals(2, logger.stats().droppedInvalidId());
    }

    /**
     * The pre-check must not be too strict: an ID over the limit as written is still valid if it
     * normalizes to fit. "{id}.json" leaves 250 chars; NFC composes omega with three combining
     * marks (4 chars) into one letter, so 70 of them (280 chars) normalize to 70.
     */
    @Test
    void normalizeIdsAcceptsAnIdThatNormalizationShrinksToFit() {
        AsyncLogger logger = manual(builder().normalizeIds().flushIntervalMillis(0));
        String omegas = "ᾯ".repeat(70);
        String normalized = FileNamePattern.normalize(omegas);
        assertEquals(70, normalized.length(), "the sample composes 4:1");
        assertTrue(omegas.length() > 250 && omegas.length() <= 250 * FileNamePattern.MAX_NORMALIZATION_SHRINK);

        assertTrue(logger.log(omegas, json(1)));
        logger.worker(0).runOnce();
        assertEquals(json(1) + "\n", content(normalized));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    static com.sun.management.ThreadMXBean allocationCounter() {
        assumeTrue(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean,
                "needs com.sun.management.ThreadMXBean");
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(mx.isThreadAllocatedMemorySupported(), "needs per-thread allocation counting");
        mx.setThreadAllocatedMemoryEnabled(true);
        return mx;
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

    /**
     * After close, a message is rejected for being after close, whatever else is wrong with it:
     * it used to count as accepted and dropped for its ID or size, and its one-time warning was
     * handed to a worker that no longer existed (review 19, Q1).
     */
    @Test
    void rejectionsAfterCloseCountAsRejectedClosed() {
        AsyncLogger logger = manual(builder().maxQueuedChars(10));
        assertTrue(logger.log("a", json(1)));
        logger.close(Duration.ZERO);
        drainAndFinish(logger);

        assertFalse(logger.log("a".repeat(251), json(2)), "an id too long for a file name");
        assertFalse(logger.log("a", "x".repeat(11)), "a message too large for the queue");
        assertFalse(logger.log("a", json(3)), "an ordinary message");
        LoggerStats s = logger.stats();
        assertEquals(3, s.rejectedClosed(), s::toString);
        assertEquals(1, s.accepted(), s::toString);
        assertEquals(0, s.droppedInvalidId(), s::toString);
        assertEquals(0, s.droppedTooLarge(), s::toString);
        assertEquals(0, logger.droppedCount());
        assertTrue(warnings.isEmpty(), warnings::toString);
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
