package org.example.efficientlogger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Async mode on real threads: waking the worker, flush, close, the shutdown hook and interrupts.
 * Every wait has a deadline, and no result depends on sleeping. The same code driven step by
 * step is tested in AsyncLoggerTest.
 */
@Timeout(value = 2, unit = java.util.concurrent.TimeUnit.MINUTES)
class AsyncThreadsTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @TempDir
    Path dir;

    private final List<String> warnings = Collections.synchronizedList(new ArrayList<>());
    private final List<Gate> gates = new ArrayList<>();
    private final List<EfficientLogger> loggers = new ArrayList<>();

    @AfterEach
    void releaseAndClose() {
        for (Gate gate : gates) {
            gate.open();
        }
        for (EfficientLogger logger : loggers) {
            logger.close(WAIT); // so @TempDir can delete the files on Windows
        }
    }

    // ---- the public API ----

    @Test
    void buildIsAsynchronousByDefault() throws Exception {
        Thread worker;
        try (EfficientLogger logger = builder().build()) {
            assertTrue(logger instanceof AsyncLogger);
            assertTrue(logger.log("a", json(1)));
            assertTrue(logger.log("b", json(2)));
            assertTrue(logger.log("a", json(3)));
            assertTrue(logger.flush());
            assertEquals(List.of(json(1), json(3)), lines("a"));
            assertEquals(List.of(json(2)), lines("b"));

            worker = ((AsyncLogger) logger).thread(0);
            assertTrue(worker.isDaemon());
            assertTrue(worker.getName().matches("efficient-logger-\\d+-worker-0"), worker.getName());
        }
        worker.join(WAIT.toMillis());
        assertFalse(worker.isAlive());
        assertTrue(warnings.isEmpty(), warnings::toString);
    }

    @Test
    void appendsAcrossRestarts() throws IOException {
        try (EfficientLogger logger = builder().build()) {
            logger.log("a", json(1));
        }
        try (EfficientLogger logger = builder().workerThreads(3).build()) {
            logger.log("a", json(2));
        }
        assertEquals(List.of(json(1), json(2)), lines("a"));
    }

    @Test
    void worksWithTheLargestMaxOpenFiles() throws IOException {
        try (EfficientLogger logger = builder().maxOpenFiles(Integer.MAX_VALUE).build()) {
            assertTrue(logger.log("a", json(1)));
        }
        assertEquals(List.of(json(1)), lines("a"));
    }

    // ---- flush ----

    @ParameterizedTest(name = "{0} worker(s)")
    @ValueSource(ints = {1, 3})
    void flushCoversEveryMessageLoggedBeforeIt(int workers) throws Exception {
        AsyncLogger logger = start(builder().workerThreads(workers).flushIntervalMillis(60_000));
        int producers = 4;
        AtomicLongArray accepted = new AtomicLongArray(producers);
        Thread[] threads = new Thread[producers];
        for (int p = 0; p < producers; p++) {
            int producer = p;
            threads[p] = new Thread(() -> {
                for (int seq = 0; seq < 100_000; seq++) {
                    if (logger.log("p" + producer, json(seq))) {
                        accepted.incrementAndGet(producer);
                    }
                }
            });
            threads[p].start();
        }
        try {
            for (int round = 0; round < 20; round++) {
                long[] before = new long[producers];
                for (int p = 0; p < producers; p++) {
                    before[p] = accepted.get(p);
                }
                assertTrue(logger.flush(WAIT), "flush timed out");
                for (int p = 0; p < producers; p++) {
                    long onDisk = countLines(dir.resolve("p" + p + ".json"));
                    assertTrue(onDisk >= before[p], "round " + round + ", producer " + p + ": " + onDisk
                            + " lines on disk after flush, but " + before[p] + " messages were accepted before it");
                }
            }
        } finally {
            for (Thread t : threads) {
                t.join();
            }
        }
        assertTrue(logger.close(WAIT));
        for (int p = 0; p < producers; p++) {
            List<String> lines = lines("p" + p);
            assertEquals(accepted.get(p), lines.size());
            int last = -1;
            for (String line : lines) {
                int n = Integer.parseInt(line, 5, line.length() - 1, 10); // {"n":123}
                assertTrue(n > last, "out of order: " + n + " after " + last);
                last = n;
            }
        }
    }

    @Test
    void flushReturnsFalseIfItTimesOut() throws IOException {
        Gate gate = gate(1);
        AsyncLogger logger = startGated(builder(), gate);
        logger.log("a", json(1));
        assertFalse(logger.flush(Duration.ofMillis(100)));

        gate.open();
        assertTrue(logger.flush(WAIT));
        assertEquals(List.of(json(1)), lines("a"));
        assertTrue(logger.close(WAIT));
        assertTrue(logger.flush(WAIT), "nothing left to wait for after close");
    }

    // ---- close ----

    @Test
    void closeWaitsForTheQueueToDrain() throws Exception {
        Gate gate = gate(1);
        AsyncLogger logger = startGated(builder(), gate);
        for (int i = 0; i < 1000; i++) {
            assertTrue(logger.log("a", json(i)));
        }

        CompletableFuture<Boolean> closed = CompletableFuture.supplyAsync(() -> logger.close(WAIT));
        await(logger::closing, "close to start");
        assertFalse(logger.log("a", json(-1)), "log() after close started");
        assertFalse(closed.isDone(), "close() returned while messages were still queued");

        gate.open();
        assertTrue(closed.get(WAIT.toSeconds(), SECONDS));
        assertEquals(jsons(0, 1000), lines("a"));
        LoggerStats s = logger.stats();
        assertEquals(1000, s.accepted());
        assertEquals(1000, s.written());
        assertEquals(1, s.rejectedClosed());
        assertEquals(0, s.queuedMessages());
        assertEquals(0, s.openFiles());
        assertTrue(warnings.isEmpty(), warnings::toString);
    }

    @Test
    void closeTimesOutButTheRestIsWrittenInTheBackground() throws IOException {
        Gate gate = gate(1);
        AsyncLogger logger = startGated(builder(), gate);
        for (int i = 0; i < 10; i++) {
            logger.log("a", json(i));
        }
        assertFalse(logger.close(Duration.ofMillis(200)));
        assertEquals(List.of("close didn't finish within 200 ms; the remaining 10 messages will be written in"
                + " the background"), warnings);
        assertNotNull(logger.shutdownHook(), "kept until the queue is drained, in case the JVM exits first");

        gate.open();
        assertTrue(logger.close(WAIT));
        assertEquals(jsons(0, 10), lines("a"));
        assertNull(logger.shutdownHook());
    }

    /**
     * Producers keep logging while the logger is closed. The lines on disk must be exactly the
     * messages whose {@code log} returned true: none lost, none extra, each ID's lines in order.
     */
    @ParameterizedTest(name = "{0} worker(s), {1} id(s), {2} producers")
    @CsvSource({"1, 1, flat-out", "1, 50, flat-out", "3, 1, flat-out", "3, 50, flat-out",
            "1, 1, paced", "3, 50, paced"})
    void theFilesHoldExactlyTheAcceptedMessagesWhenCloseRacesProducers(int workers, int ids, String speed)
            throws Exception {
        // Flat out, the queue is mostly full. Paced, with an eager worker, it is mostly empty, so
        // close often lands while a producer is between being admitted and publishing.
        boolean paced = speed.equals("paced");
        AsyncLogger logger = start(builder().workerThreads(workers).maxQueuedMessages(4096).eagerWakeup(paced));
        int producers = 8;
        BitSet[] accepted = new BitSet[producers];
        long[] calls = new long[producers];
        Thread[] threads = new Thread[producers];
        for (int p = 0; p < producers; p++) {
            int producer = p;
            Random random = new Random(p);
            threads[p] = new Thread(() -> {
                BitSet mine = new BitSet();
                int seq = 0;
                for (;; seq++) {
                    if (paced) {
                        spinNanos(random.nextInt(2001));
                    }
                    if (logger.log("id" + (seq % ids), "{\"p\":" + producer + ",\"s\":" + seq + "}")) {
                        mine.set(seq);
                    } else if (logger.closing()) {
                        break;
                    }
                }
                accepted[producer] = mine;
                calls[producer] = seq + 1;
            });
            threads[p].start();
        }
        await(() -> logger.stats().accepted() >= 50_000, "50,000 messages to be accepted");
        assertTrue(logger.close(WAIT));
        for (Thread t : threads) {
            t.join();
        }
        long acceptedCount = 0;
        for (BitSet b : accepted) {
            acceptedCount += b.cardinality();
        }
        LoggerStats stats = logger.stats();
        assertEquals(acceptedCount, stats.written(), () -> stats + " " + warnings);

        BitSet[] onDisk = new BitSet[producers];
        for (int p = 0; p < producers; p++) {
            onDisk[p] = new BitSet();
        }
        for (int k = 0; k < ids; k++) {
            String id = "id" + k;
            int[] last = new int[producers];
            java.util.Arrays.fill(last, -1);
            for (String line : lines(id)) {
                int comma = line.indexOf(',');
                int p = Integer.parseInt(line, 5, comma, 10); // {"p":3,"s":17}
                int s = Integer.parseInt(line, comma + 5, line.length() - 1, 10);
                assertEquals(k, s % ids, () -> id + " holds a line for another id: " + line);
                assertTrue(s > last[p], () -> id + ": producer " + p + "'s line " + s + " came after " + last[p]);
                last[p] = s;
                onDisk[p].set(s);
            }
        }
        long totalAccepted = 0;
        long totalCalls = 0;
        for (int p = 0; p < producers; p++) {
            BitSet diff = (BitSet) accepted[p].clone();
            diff.xor(onDisk[p]);
            int producer = p;
            assertTrue(diff.isEmpty(), () -> "producer " + producer + ": " + diff.cardinality() + " lines differ from the"
                    + " log() calls that returned true, the first at seq " + diff.nextSetBit(0)
                    + (accepted[producer].get(diff.nextSetBit(0)) ? " (accepted, not on disk)" : " (on disk, not accepted)"));
            totalAccepted += accepted[p].cardinality();
            totalCalls += calls[p];
        }

        LoggerStats s = logger.stats();
        assertEquals(totalAccepted, s.accepted());
        assertEquals(totalAccepted, s.written());
        assertEquals(totalCalls, s.accepted() + s.droppedQueueFull() + s.rejectedClosed(), s::toString);
        assertEquals(0, s.queuedMessages());
        assertEquals(0, s.openFiles());
        for (String w : warnings) {
            assertTrue(w.startsWith("queue ") || w.startsWith("dropped "), w);
        }
    }

    /**
     * A producer admitted just before close may not have put its message in the ring yet. The
     * workers must wait for it rather than stop at an empty ring.
     */
    @Test
    void closeWaitsForAMessageAdmittedButNotYetQueued() throws IOException {
        AsyncLogger logger = start(builder());
        String json = json(1);
        long admitted = logger.admission().tryAcquire(json.length()); // what log() does first...
        assertTrue(admitted > 0);

        assertFalse(logger.close(Duration.ofMillis(200)), "close finished with a message still on its way");
        assertFalse(logger.worker(0).terminated());
        assertEquals(List.of("close didn't finish within 200 ms; the remaining 1 message will be written in the"
                + " background"), warnings);

        logger.worker(0).ring.offer("a", json, admitted); // ...and then this
        assertTrue(logger.close(WAIT));
        assertEquals(List.of(json), lines("a"));
    }

    @Test
    void concurrentClosesAllWaitForTheDrain() throws Exception {
        Gate gate = gate(2);
        AsyncLogger logger = startGated(builder().workerThreads(2), gate);
        for (int i = 0; i < 100; i++) {
            assertTrue(logger.log("id" + i, json(i)));
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Boolean>> closes = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                closes.add(pool.submit(() -> logger.close(WAIT)));
            }
            await(logger::closing, "close to start");
            gate.open();
            for (Future<Boolean> f : closes) {
                assertTrue(f.get(WAIT.toSeconds(), SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue(logger.close(Duration.ZERO), "already closed");
        for (int i = 0; i < 100; i++) {
            assertEquals(List.of(json(i)), lines("id" + i));
        }
        assertTrue(warnings.isEmpty(), warnings::toString);
    }

    @Test
    void closeRemovesTheShutdownHook() {
        AsyncLogger logger = start(builder());
        Thread hook = logger.shutdownHook();
        assertNotNull(hook);
        assertTrue(logger.close(WAIT));
        assertNull(logger.shutdownHook());
        assertFalse(Runtime.getRuntime().removeShutdownHook(hook), "the hook is still registered");

        assertNull(start(builder().registerShutdownHook(false)).shutdownHook());
    }

    /**
     * A warning handler runs on a worker. There, flush and close can't wait for the worker (it is
     * the caller), so they return false at once instead of blocking until their timeout.
     */
    @Test
    void aWarningHandlerOnAWorkerMayFlushAndClose() throws IOException {
        AtomicReference<EfficientLogger> self = new AtomicReference<>();
        List<Object> results = Collections.synchronizedList(new ArrayList<>());
        WarningHandler handler = (message, cause) -> {
            warnings.add(message);
            if (message.startsWith("dropping messages for invalid id")) {
                long start = System.nanoTime();
                results.add(self.get().flush(Duration.ofSeconds(30)));
                results.add(self.get().close(Duration.ofSeconds(30)));
                results.add(self.get().log("x", "{}"));
                results.add(System.nanoTime() - start);
            }
        };
        AsyncLogger logger = start(builder().workerThreads(2).warningHandler(handler));
        self.set(logger);
        for (int i = 0; i < 100; i++) {
            assertTrue(logger.log("id" + i, json(i)));
        }
        assertTrue(logger.log("a/b", json(-1)), "accepted; the worker finds the id is invalid");

        await(() -> results.size() == 4, "the warning handler to finish");
        assertTrue(logger.closing(), "the handler closed the logger");
        assertEquals(false, results.get(0), "flush from a worker");
        assertEquals(false, results.get(1), "close from a worker");
        assertEquals(false, results.get(2), "log after close");
        assertTrue((long) results.get(3) < SECONDS.toNanos(5), "the handler was blocked for " + results.get(3) + " ns");

        assertTrue(logger.close(WAIT));
        for (int i = 0; i < 100; i++) {
            assertEquals(List.of(json(i)), lines("id" + i));
        }
        LoggerStats s = logger.stats();
        assertEquals(101, s.accepted());
        assertEquals(100, s.written());
        assertEquals(1, s.droppedInvalidId());
    }

    // ---- interrupts ----

    @Test
    void interruptingTheWorkerLosesNothingAndItStillSleeps() throws Exception {
        List<Thread> created = Collections.synchronizedList(new ArrayList<>());
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "interrupted-worker");
            t.setDaemon(true);
            created.add(t);
            return t;
        };
        AsyncLogger logger = start(builder().threadFactory(factory));
        Thread worker = created.get(0);
        for (int i = 0; i < 20_000; i++) {
            assertTrue(logger.log("a", json(i)));
            if (i % 10 == 0) {
                worker.interrupt();
            }
        }
        assertTrue(logger.flush(WAIT));
        assertEquals(20_000, lines("a").size());

        // A leftover interrupt must not leave the worker spinning: park() returns at once while it's set.
        for (int i = 0; i < 100; i++) {
            worker.interrupt();
        }
        await(() -> worker.getState() == Thread.State.WAITING || worker.getState() == Thread.State.TIMED_WAITING,
                "the worker to sleep");
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        if (mx.isThreadCpuTimeSupported() && mx.isThreadCpuTimeEnabled()) {
            long before = mx.getThreadCpuTime(worker.getId());
            LockSupport.parkNanos(MILLISECONDS.toNanos(500)); // a measuring window, not a wait for a result
            long used = mx.getThreadCpuTime(worker.getId()) - before;
            assertTrue(used < MILLISECONDS.toNanos(100), "the idle worker used " + used / 1_000_000 + " ms of CPU in 500 ms");
        }

        assertTrue(logger.close(WAIT));
        assertEquals(jsons(0, 20_000), lines("a"));
    }

    @Test
    void interruptedCallersKeepTheirInterruptFlag() throws IOException {
        Gate gate = gate(1);
        AsyncLogger logger = startGated(builder(), gate);
        Thread.currentThread().interrupt();
        try {
            assertTrue(logger.log("a", json(1)), "log() doesn't care about interrupts");
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(logger.flush(WAIT), "flush() gives up when interrupted");
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(logger.close(WAIT), "close() gives up when interrupted");
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        gate.open();
        assertTrue(logger.close(WAIT));
        assertEquals(List.of(json(1)), lines("a"));
        assertTrue(warnings.isEmpty(), warnings::toString);
    }

    // ---- overflow ----

    @Test
    void anOverflowIsReportedOnceAndItsEndOnce() throws IOException {
        Gate gate = gate(1);
        AsyncLogger logger = startGated(builder().maxQueuedMessages(8), gate);
        for (int i = 1; i <= 8; i++) {
            assertTrue(logger.log("a", json(i)));
        }
        for (int i = 9; i <= 13; i++) {
            assertFalse(logger.log("a", json(i)));
        }
        assertEquals(5, logger.droppedCount());

        gate.open();
        assertTrue(logger.flush(WAIT));
        assertEquals(jsons(1, 9), lines("a"));
        await(() -> warnings.size() >= 2, "the end of the overflow to be reported");
        assertTrue(logger.close(WAIT));
        assertEquals(List.of(
                "queue full (maxQueuedMessages=8, maxQueuedChars=16777216); dropping messages until it is half empty",
                "queue recovered; dropped 5 messages (5 in total)"), warnings);
    }

    // ---- a log() call cut off by a fatal error (audit F7) ----

    @Test
    void aSlotLeftUnpublishedDoesNotWedgeOrBurnTheWorker() throws Exception {
        // A producer cut off between claiming a ring slot and publishing it leaves the slot null
        // forever. The worker can't drain past it, but it must not spin on a core, and close must
        // still finish instead of hanging.
        AsyncLogger logger = start(builder().flushIntervalMillis(60_000), nullSinks());
        logger.worker(0).ring.claimWithoutPublishingForTest();
        assertTrue(logger.log("a", json(1)), "queued behind the unpublished slot, so never drained");

        Thread worker = logger.thread(0);
        await(() -> worker.getState() == Thread.State.WAITING || worker.getState() == Thread.State.TIMED_WAITING,
                "the worker to back off from the stuck slot");
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        if (mx.isThreadCpuTimeSupported() && mx.isThreadCpuTimeEnabled()) {
            long before = mx.getThreadCpuTime(worker.getId());
            LockSupport.parkNanos(MILLISECONDS.toNanos(500)); // a measuring window, not a wait for a result
            long used = mx.getThreadCpuTime(worker.getId()) - before;
            assertTrue(used < MILLISECONDS.toNanos(250), "the stuck worker used " + used / 1_000_000 + " ms of CPU in 500 ms");
        }

        assertTrue(logger.close(WAIT), "close finishes once the queue stops draining, rather than hanging");
        worker.join(WAIT.toMillis());
        assertFalse(worker.isAlive());
        assertEquals(List.of("close stopped waiting with messages still queued; a log() call was probably cut off"
                + " by a fatal error such as StackOverflowError, so its message never arrived"), warnings);
        assertEquals(List.of(), lines("a"), "the message behind the stuck slot was never written");
    }

    @Test
    void anAdmittedMessageThatNeverReachesARingDoesNotHangClose() throws Exception {
        // A producer cut off after being admitted but before claiming a ring slot leaves the queue
        // size permanently above zero, with nothing in any ring to drain.
        AsyncLogger logger = start(builder());
        assertTrue(logger.log("a", json(1)));
        assertTrue(logger.flush(WAIT));
        assertEquals(1, logger.worker(0).files.written());

        assertTrue(logger.admission().tryAcquire(5) > 0, "simulate an admitted-but-unpublished message");
        assertEquals(1, logger.stats().queuedMessages(), "it shows as still queued");

        assertTrue(logger.close(WAIT), "close finishes within the grace instead of waiting forever");
        assertEquals(List.of("close stopped waiting with messages still queued; a log() call was probably cut off"
                + " by a fatal error such as StackOverflowError, so its message never arrived"), warnings);
        assertEquals(List.of(json(1)), lines("a"));
    }

    // ---- waking the worker ----

    /**
     * In eager mode the worker sleeps until woken, so a single lost wake-up leaves a message
     * unwritten for good. The jitter moves each log() call around the worker's way to sleep.
     */
    @Test
    void anEagerWorkerNeverMissesAWakeUp() {
        AsyncLogger logger = start(builder().flushIntervalMillis(0), nullSinks());
        FileTable files = logger.worker(0).files;
        Random random = new Random(1);
        for (int i = 0; i < 100_000; i++) {
            spinNanos(random.nextInt(2001));
            assertTrue(logger.log("a", "{}"));
            long expected = i + 1;
            spinUntil(() -> files.written() == expected, "message " + expected + " to be written (a lost wake-up?)");
        }
    }

    @Test
    void anEagerWorkerNeverMissesAWakeUpFromConcurrentProducers() throws Exception {
        AsyncLogger logger = start(builder().flushIntervalMillis(0), nullSinks());
        FileTable files = logger.worker(0).files;
        int producers = 4;
        int rounds = 20_000;
        CyclicBarrier barrier = new CyclicBarrier(producers + 1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] threads = new Thread[producers];
        for (int p = 0; p < producers; p++) {
            String id = "p" + p;
            Random random = new Random(p);
            threads[p] = new Thread(() -> {
                try {
                    for (int r = 0; r < rounds; r++) {
                        barrier.await(10, SECONDS);
                        spinNanos(random.nextInt(2001));
                        if (!logger.log(id, "{}")) {
                            throw new AssertionError("dropped");
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
            threads[p].setDaemon(true);
            threads[p].start();
        }
        for (int r = 0; r < rounds; r++) {
            barrier.await(10, SECONDS);
            long expected = (long) producers * (r + 1);
            spinUntil(() -> files.written() == expected, "round " + r + "'s messages to be written (a lost wake-up?)");
        }
        for (Thread t : threads) {
            t.join(WAIT.toMillis());
        }
        assertNull(failure.get());
    }

    /**
     * Not eager: a sleeping worker is woken only once a quarter of the queue is used (16 of 64
     * messages). Smaller trickles wait for the flush interval.
     */
    @Test
    void aSleepingWorkerIsWokenOnceAQuarterOfTheQueueIsUsed() {
        AsyncLogger logger = start(builder().maxQueuedMessages(64).flushIntervalMillis(60_000), nullSinks());
        Worker worker = logger.worker(0);
        for (int round = 0; round < 500; round++) {
            spinUntil(() -> worker.ring.parkedState() == MpscRing.TIMED, "the worker to sleep");
            for (int i = 0; i < 15; i++) {
                assertTrue(logger.log("a", "{}"));
            }
            assertEquals(MpscRing.TIMED, worker.ring.parkedState(), "woken with 15 messages queued");
            assertTrue(logger.log("a", "{}"));
            long expected = 16L * (round + 1);
            spinUntil(() -> worker.files.written() == expected, "the 16th message to wake the worker");
        }
    }

    @Test
    void aDeepSleepingWorkerIsWokenByOneMessage() {
        AsyncLogger logger = start(builder().flushIntervalMillis(1), nullSinks());
        Worker worker = logger.worker(0);
        for (int round = 0; round < 5; round++) {
            await(() -> worker.ring.parkedState() == MpscRing.DEEP,
                    "the worker to sleep until woken, after " + Worker.DEEP_SLEEP_AFTER + " idle wake-ups");
            assertTrue(logger.log("a", "{}"));
            long expected = round + 1;
            spinUntil(() -> worker.files.written() == expected, "one message to wake the worker");
        }
    }

    // ---- JVM exit ----

    @ParameterizedTest(name = "main {0}s")
    @ValueSource(strings = {"return", "exit"})
    void theShutdownHookWritesEverythingWhenTheJvmExits(String mode) throws Exception {
        assertEquals(10_000, runShutdownMain(mode, 10_000));
    }

    /** Shows the test above can fail: without the hook, nothing the worker held back is written. */
    @Test
    void withoutTheShutdownHookQueuedMessagesAreLostAtExit() throws Exception {
        assertEquals(0, runShutdownMain("nohook", 10_000));
    }

    // ---- starting the threads ----

    @Test
    void aThreadThatFailsToStartStopsTheOnesAlreadyStarted() throws Exception {
        List<Thread> created = new ArrayList<>();
        OutOfMemoryError failure = new OutOfMemoryError("unable to create native thread (test)");
        ThreadFactory factory = r -> {
            Thread t = created.isEmpty() ? new Thread(r) : new Thread(r) {
                @Override
                public synchronized void start() {
                    throw failure;
                }
            };
            t.setDaemon(true);
            created.add(t);
            return t;
        };
        OutOfMemoryError e = assertThrows(OutOfMemoryError.class,
                () -> builder().workerThreads(2).threadFactory(factory).build());
        assertSame(failure, e);
        Thread first = created.get(0);
        first.join(WAIT.toMillis());
        assertFalse(first.isAlive(), "the worker that started is still running");
    }

    @Test
    void aThreadFactoryThatReturnsNullIsAnError() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> builder().threadFactory(r -> null).build());
        assertEquals("threadFactory returned null", e.getMessage());
    }

    // ---- helpers ----

    private EfficientLoggerBuilder builder() {
        return EfficientLogger.builder(dir, "{id}.json")
                .warningHandler((message, cause) -> warnings.add(cause == null ? message : message + " | " + cause));
    }

    private AsyncLogger start(EfficientLoggerBuilder b) {
        return start(b, new TestHooks());
    }

    private AsyncLogger start(EfficientLoggerBuilder b, TestHooks hooks) {
        AsyncLogger logger = (AsyncLogger) b.build(hooks);
        loggers.add(logger);
        return logger;
    }

    /** A logger whose workers are all held at the gate before their first batch. */
    private AsyncLogger startGated(EfficientLoggerBuilder b, Gate gate) {
        TestHooks hooks = new TestHooks();
        hooks.beforeBatch = gate;
        AsyncLogger logger = start(b, hooks);
        gate.awaitEntered();
        return logger;
    }

    private Gate gate(int workers) {
        Gate gate = new Gate(workers);
        gates.add(gate);
        return gate;
    }

    private static TestHooks nullSinks() {
        TestHooks hooks = new TestHooks();
        hooks.sinks = new NullSinks();
        return hooks;
    }

    private List<String> lines(String id) throws IOException {
        return lines(dir.resolve(id + ".json"));
    }

    private static List<String> lines(Path file) throws IOException {
        return Files.exists(file) ? Files.readAllLines(file, UTF_8) : List.of();
    }

    /** Complete lines in a file that may be being written. */
    private static long countLines(Path file) throws IOException {
        if (!Files.exists(file)) {
            return 0;
        }
        long n = 0;
        for (byte b : Files.readAllBytes(file)) {
            if (b == '\n') {
                n++;
            }
        }
        return n;
    }

    private static String json(int n) {
        return "{\"n\":" + n + "}";
    }

    /** json(from) to json(to - 1). */
    private static List<String> jsons(int from, int to) {
        List<String> list = new ArrayList<>();
        for (int i = from; i < to; i++) {
            list.add(json(i));
        }
        return list;
    }

    /** Polls, for waits where a millisecond doesn't matter. */
    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) {
                fail("timed out waiting for " + what);
            }
            LockSupport.parkNanos(MILLISECONDS.toNanos(1));
        }
    }

    /** Spins, for waits that are part of a race being tested. */
    private static void spinUntil(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + SECONDS.toNanos(5);
        for (int spins = 0; !condition.getAsBoolean(); spins++) {
            Thread.onSpinWait();
            if ((spins & 1023) == 1023 && System.nanoTime() - deadline > 0) {
                fail("timed out after 5 s waiting for " + what);
            }
        }
    }

    private static void spinNanos(long nanos) {
        long end = System.nanoTime() + nanos;
        while (System.nanoTime() - end < 0) {
            Thread.onSpinWait();
        }
    }

    /**
     * Runs {@link ShutdownMain} in a new JVM, which logs {@code count} messages over 10 ids and
     * exits without closing, and returns the number of lines it left on disk.
     */
    private int runShutdownMain(String mode, int count) throws Exception {
        String java = ProcessHandle.current().info().command().orElseThrow();
        String classpath = codeSource(EfficientLogger.class) + File.pathSeparator + codeSource(ShutdownMain.class);
        Path logs = dir.resolve("logs");
        Path output = dir.resolve("output.txt");
        Process child = new ProcessBuilder(java, "-Xmx64m", "-cp", classpath, ShutdownMain.class.getName(),
                logs.toString(), Integer.toString(count), mode)
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        if (!child.waitFor(60, SECONDS)) {
            child.destroyForcibly();
            fail("the child JVM didn't exit");
        }
        String out = Files.readString(output);
        assertEquals(0, child.exitValue(), out);
        assertTrue(out.contains("accepted=" + count), out);
        assertFalse(out.contains("[EfficientLogger]"), out);

        int total = 0;
        for (int k = 0; k < 10; k++) {
            List<String> lines = lines(logs.resolve("id" + k + ".json"));
            for (int j = 0; j < lines.size(); j++) {
                assertEquals(json(k + 10 * j), lines.get(j), "id" + k + " line " + j);
            }
            total += lines.size();
        }
        return total;
    }

    private static String codeSource(Class<?> c) throws Exception {
        return Paths.get(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    /** Holds every worker before its next batch until opened. */
    static final class Gate implements Runnable {
        private final CountDownLatch entered;
        private final CountDownLatch opened = new CountDownLatch(1);

        Gate(int workers) {
            entered = new CountDownLatch(workers);
        }

        @Override
        public void run() {
            if (opened.getCount() == 0) {
                return;
            }
            entered.countDown();
            boolean interrupted = false;
            for (;;) {
                try {
                    opened.await();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        void awaitEntered() {
            try {
                assertTrue(entered.await(10, SECONDS), "the workers didn't reach the gate");
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }

        void open() {
            opened.countDown();
        }
    }

    /** Discards what is written. Has no state, so any thread may use it. */
    static final class NullSinks implements SinkFactory {
        @Override
        public Sink open(Path path) {
            return new Sink() {
                @Override
                public void write(byte[] b, int off, int len) {}

                @Override
                public void close() {}
            };
        }

        @Override
        public int lastByte(Path path) {
            return -1;
        }

        @Override
        public boolean createDirectoryIfMissing(Path dir) {
            return false;
        }
    }
}
