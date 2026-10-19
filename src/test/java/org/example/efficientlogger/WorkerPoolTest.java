package org.example.efficientlogger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Loggers sharing a {@link WorkerPool}: the thread count is bounded by the pool, not by the number
 * of loggers; threads come and go with the loggers; and every logger on a shared thread still gets
 * its messages written, flushed and closed.
 */
@Timeout(value = 2, unit = java.util.concurrent.TimeUnit.MINUTES)
class WorkerPoolTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @TempDir
    Path dir;

    private final List<String> warnings = Collections.synchronizedList(new ArrayList<>());
    private final List<EfficientLogger> loggers = new ArrayList<>();

    @AfterEach
    void close() {
        for (EfficientLogger logger : loggers) {
            logger.close(WAIT);
        }
    }

    @Test
    void manyLoggersShareThePoolsThreads() throws Exception {
        WorkerPool pool = WorkerPool.create(2);
        List<AsyncLogger> many = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            many.add(start(builder("l" + i).workerPool(pool)));
        }
        assertEquals(2, pool.threads(), "10 loggers, 2 threads");
        Set<Thread> threads = new HashSet<>();
        for (AsyncLogger logger : many) {
            threads.add(logger.thread(0));
        }
        assertEquals(2, threads.size());

        for (int round = 0; round < 100; round++) {
            for (int i = 0; i < many.size(); i++) {
                assertTrue(many.get(i).log("a", json(i * 1000 + round)));
            }
        }
        for (AsyncLogger logger : many) {
            assertTrue(logger.flush(WAIT));
        }
        for (int i = 0; i < many.size(); i++) {
            assertEquals(100, lines("l" + i, "a").size(), "logger " + i);
            assertEquals(100, many.get(i).stats().written());
        }
        for (AsyncLogger logger : many) {
            assertTrue(logger.close(WAIT));
        }
        await(() -> pool.threads() == 0, "the threads to exit once the last logger has closed");
        assertTrue(warnings.isEmpty(), warnings::toString);
    }

    @Test
    void aLoggersWorkersAreSpreadOverTheThreads() {
        WorkerPool pool = WorkerPool.create(3);
        AsyncLogger logger = start(builder("l").workerThreads(3).workerPool(pool));
        Set<Thread> threads = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            threads.add(logger.thread(i));
        }
        assertEquals(3, threads.size(), "one thread per worker while the pool has room");
        assertEquals(3, pool.threads());

        // A second logger lands on the least loaded threads: all are equal, so anywhere; the pool grows no further.
        AsyncLogger second = start(builder("m").workerThreads(2).workerPool(pool));
        assertEquals(3, pool.threads());
        assertTrue(threads.contains(second.thread(0)));
        assertTrue(threads.contains(second.thread(1)));
        assertNotSame(second.thread(0), second.thread(1), "its two workers go to different threads");
    }

    @Test
    void moreWorkersThanThreadsStillWriteEverythingInOrder() throws Exception {
        WorkerPool pool = WorkerPool.create(1);
        AsyncLogger logger = start(builder("l").workerThreads(4).workerPool(pool));
        for (int i = 0; i < 4; i++) {
            assertSame(logger.thread(0), logger.thread(i), "one thread serves all four workers");
        }
        for (int i = 0; i < 5_000; i++) {
            assertTrue(logger.log("id" + (i % 7), json(i)));
        }
        assertTrue(logger.flush(WAIT));
        int total = 0;
        for (int k = 0; k < 7; k++) {
            List<String> lines = lines("l", "id" + k);
            for (int j = 0; j < lines.size(); j++) {
                assertEquals(json(k + 7 * j), lines.get(j), "id" + k + " line " + j);
            }
            total += lines.size();
        }
        assertEquals(5_000, total);
        assertTrue(logger.close(WAIT));
        assertEquals(5_000, logger.stats().written());
    }

    @Test
    void aThreadSleepsOnlyWhenNoneOfItsLoggersHasWork() throws Exception {
        // Two loggers on one thread: a trickle to one must not keep the other's messages waiting
        // longer than its flush interval, and the thread must sleep between the trickles.
        WorkerPool pool = WorkerPool.create(1);
        AsyncLogger quick = start(builder("q").flushIntervalMillis(10).workerPool(pool));
        AsyncLogger slow = start(builder("s").flushIntervalMillis(60_000).workerPool(pool));
        Thread thread = quick.thread(0);
        assertSame(thread, slow.thread(0));

        for (int round = 1; round <= 20; round++) {
            assertTrue(quick.log("a", json(round)));
            assertTrue(slow.log("a", json(round)));
            long expected = round;
            await(() -> quick.stats().written() == expected, "the quick logger's flush interval to write message " + expected);
        }
        // The slow logger's messages are taken from its ring on the same passes, even though
        // nothing woke the thread for them, and the thread never sleeps holding dirty buffers, so
        // once it sleeps they are on disk too.
        await(() -> slow.stats().written() == 20, "the slow logger's messages to be taken along");
        await(() -> thread.getState() == Thread.State.WAITING || thread.getState() == Thread.State.TIMED_WAITING,
                "the shared thread to sleep");
        assertEquals(20, lines("s", "a").size());
        assertTrue(slow.flush(WAIT));
    }

    @Test
    void aClosedLoggersThreadCarriesOnServingTheOthers() throws Exception {
        WorkerPool pool = WorkerPool.create(1);
        AsyncLogger first = start(builder("f").workerPool(pool));
        AsyncLogger second = start(builder("s").workerPool(pool));
        Thread thread = first.thread(0);
        assertSame(thread, second.thread(0));

        assertTrue(first.log("a", json(1)));
        assertTrue(first.close(WAIT));
        assertEquals(List.of(json(1)), lines("f", "a"));
        assertTrue(thread.isAlive(), "the second logger still needs it");
        assertEquals(1, pool.threads());

        assertTrue(second.log("a", json(2)));
        assertTrue(second.flush(WAIT));
        assertEquals(List.of(json(2)), lines("s", "a"));
        assertTrue(second.close(WAIT));
        thread.join(WAIT.toMillis());
        assertFalse(thread.isAlive());
        assertEquals(0, pool.threads());

        // And a later logger starts a fresh thread in the same pool.
        AsyncLogger third = start(builder("t").workerPool(pool));
        assertNotSame(thread, third.thread(0));
        assertTrue(third.log("a", json(3)));
        assertTrue(third.flush(WAIT));
        assertEquals(List.of(json(3)), lines("t", "a"));
    }

    /**
     * A warning handler runs on a pool thread. From there, flush and close on any logger that
     * thread serves can't wait (the drain needs that very thread), so they return false at once,
     * whichever logger raised the warning.
     */
    @Test
    void aWarningHandlerOnASharedThreadCannotWaitForAnotherLoggerOnIt() throws IOException {
        WorkerPool pool = WorkerPool.create(1);
        AtomicReference<AsyncLogger> other = new AtomicReference<>();
        List<Object> results = Collections.synchronizedList(new ArrayList<>());
        WarningHandler handler = (message, cause) -> {
            warnings.add(message);
            if (message.startsWith("dropping messages for invalid id")) {
                long start = System.nanoTime();
                results.add(other.get().flush(Duration.ofSeconds(30)));
                results.add(other.get().close(Duration.ofSeconds(30)));
                results.add(System.nanoTime() - start);
            }
        };
        AsyncLogger raising = start(builder("r").workerPool(pool).warningHandler(handler));
        AsyncLogger shared = start(builder("s").workerPool(pool));
        other.set(shared);
        assertSame(raising.thread(0), shared.thread(0));

        assertTrue(shared.log("a", json(1)));
        assertTrue(raising.log("a/b", json(-1)), "accepted; the worker finds the id is invalid");
        await(() -> results.size() == 3, "the warning handler to finish");
        assertEquals(false, results.get(0), "flush of the other logger from its thread");
        assertEquals(false, results.get(1), "close of the other logger from its thread");
        assertTrue((long) results.get(2) < SECONDS.toNanos(5), "the handler was blocked for " + results.get(2) + " ns");

        assertTrue(shared.closing(), "the handler closed it");
        assertTrue(shared.close(WAIT), "and the thread finished closing it once the handler returned");
        assertEquals(List.of(json(1)), lines("s", "a"));
        assertTrue(raising.close(WAIT));
    }

    /**
     * A thread with nothing to do sleeps until a producer wakes it, and producers wake a thread only
     * through a ring it has announced on. A logger that arrives on that thread later has a ring it
     * never announced on, so registering must wake the thread itself; otherwise the new logger's
     * messages waited, and then dropped, until one of the thread's other loggers happened to log
     * (review 19, T1).
     */
    @Test
    void aLoggerRegisteredOntoASleepingThreadIsServedAtOnce() throws Exception {
        WorkerPool pool = WorkerPool.create(1);
        AsyncLogger first = start(builder("f").flushIntervalMillis(10).workerPool(pool));
        assertTrue(first.log("a", json(1)));
        assertTrue(first.flush(WAIT));
        Thread thread = first.thread(0);
        await(() -> thread.getState() == Thread.State.WAITING, "the thread to fall into deep sleep"); // park, not parkNanos

        AsyncLogger second = start(builder("s").workerPool(pool));
        assertSame(thread, second.thread(0));
        for (int i = 1; i <= 5; i++) {
            assertTrue(second.log("a", json(i)));
        }
        await(() -> second.stats().written() == 5, "the new logger's messages to be written while its thread's other logger stays quiet");
        assertEquals(List.of(json(1), json(2), json(3), json(4), json(5)), lines("s", "a"));
        assertEquals(0, second.droppedCount());
        assertTrue(warnings.isEmpty(), warnings::toString);
    }

    @Test
    void oneShutdownHookServesEveryLogger() {
        AsyncLogger first = start(builder("f"));
        AsyncLogger second = start(builder("s"));
        Thread hook = first.shutdownHook();
        assertNotNull(hook);
        assertSame(hook, second.shutdownHook(), "both are closed by the same hook");
        assertEquals("efficient-logger-shutdown", hook.getName());

        assertTrue(first.close(WAIT));
        assertNull(first.shutdownHook());
        assertSame(hook, second.shutdownHook(), "kept while a logger still needs it");
        assertTrue(second.close(WAIT));
        assertNull(second.shutdownHook());
        assertFalse(Runtime.getRuntime().removeShutdownHook(hook), "removed once the last logger closed");

        AsyncLogger third = start(builder("t"));
        assertNotNull(third.shutdownHook());
        assertNotSame(hook, third.shutdownHook(), "a new hook once loggers come back");
        assertTrue(third.close(WAIT));
        assertNull(third.shutdownHook());
    }

    @Test
    void theHookClosesEveryLoggerAtOnceAndWaitsAtMostTheLongestTimeout() throws Exception {
        // Two loggers held before their first batch, with 300 ms close timeouts: the hook's body
        // takes about 300 ms, not 600, and reports each.
        CountDownLatch release = new CountDownLatch(1);
        TestHooks hooks = new TestHooks();
        hooks.beforeBatch = () -> {
            try {
                release.await(30, SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        WorkerPool pool = WorkerPool.create(2);
        AsyncLogger first = start(builder("f").closeTimeout(Duration.ofMillis(300)).workerPool(pool), hooks);
        AsyncLogger second = start(builder("s").closeTimeout(Duration.ofMillis(300)).workerPool(pool), hooks);
        assertTrue(first.log("a", json(1)));
        assertTrue(second.log("a", json(2)));
        Thread hook = first.shutdownHook();
        assertSame(hook, second.shutdownHook());

        long start = System.nanoTime();
        hook.run(); // the hook's body, on this thread
        long took = System.nanoTime() - start;
        assertTrue(took < MILLISECONDS.toNanos(1_500), "the hook took " + took / 1_000_000 + " ms");
        assertTrue(first.closing() && second.closing());
        assertEquals(2, warnings.size(), warnings::toString);
        for (String w : warnings) {
            assertTrue(w.startsWith("close didn't finish within 300 ms; the remaining 1 message won't be written: the JVM is exiting"), w);
        }

        release.countDown(); // let the test clean up normally
        assertTrue(first.close(WAIT));
        assertTrue(second.close(WAIT));
        assertEquals(List.of(json(1)), lines("f", "a"));
        assertEquals(List.of(json(2)), lines("s", "a"));
    }

    @Test
    void poolSizesAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> WorkerPool.create(0));
        assertThrows(IllegalArgumentException.class, () -> WorkerPool.create(WorkerPool.MAX_THREADS + 1));
        assertEquals(WorkerPool.MAX_THREADS, WorkerPool.create(WorkerPool.MAX_THREADS).maxThreads());
        assertEquals(0, WorkerPool.create(3).threads());
        assertTrue(WorkerPool.DEFAULT_SHARED_THREADS >= 1 && WorkerPool.DEFAULT_SHARED_THREADS <= 4);
        assertTrue(WorkerPool.shared().maxThreads() >= 1);
        assertSame(WorkerPool.shared(), WorkerPool.shared());
        assertTrue(WorkerPool.create(2).toString().startsWith("WorkerPool["), WorkerPool.create(2).toString());
    }

    @Test
    void threadsAreDaemonsNamedAfterThePoolAndSlot() throws Exception {
        WorkerPool pool = WorkerPool.create(2);
        AsyncLogger logger = start(builder("l").workerThreads(2).workerPool(pool));
        for (int i = 0; i < 2; i++) {
            Thread t = logger.thread(i);
            assertTrue(t.isDaemon());
            assertTrue(t.getName().matches("efficient-logger-\\d+-worker-[01]"), t.getName());
        }
        assertTrue(logger.close(WAIT));
        await(() -> pool.threads() == 0, "the threads to exit");
    }

    // ---- helpers ----

    private EfficientLoggerBuilder builder(String sub) {
        return EfficientLogger.builder(dir.resolve(sub), "{id}.json", 1 << 20)
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

    private List<String> lines(String sub, String id) throws IOException {
        Path file = dir.resolve(sub).resolve(id + ".json");
        return Files.exists(file) ? Files.readAllLines(file, UTF_8) : List.of();
    }

    private static String json(int n) {
        return "{\"n\":" + n + "}";
    }

    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) {
                fail("timed out waiting for " + what);
            }
            LockSupport.parkNanos(MILLISECONDS.toNanos(1));
        }
    }
}
