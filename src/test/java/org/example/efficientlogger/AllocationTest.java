package org.example.efficientlogger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves the hot paths allocate nothing on the Java heap, by counting the bytes each thread
 * allocates over many calls. An allocation in every call would show up as at least 16 bytes
 * times the number of calls: megabytes.
 *
 * <p>Each test runs identical rounds: two to warm up, so every path is compiled, then two
 * measured. A one-off JVM event (the JIT recompiling a method when a branch is first taken)
 * allocates a little once, so a measured round passes if either is within the allowance;
 * anything allocating per call shows up in both.
 *
 * <p>Runs in its own JVMs (Gradle tasks allocationTest and allocationTestNoEA). The second turns
 * escape analysis off, so the JIT can't hide an allocation by optimizing it away.
 */
@Tag("alloc")
class AllocationTest {

    private static final int WARMUP_ROUNDS = 2;
    private static final int MEASURED_ROUNDS = 2;
    private static final int CALLS = 1_000_000;
    private static final long CALLER_ALLOWANCE = 256;
    private static final long WORKER_ALLOWANCE = 4096;

    private static final String[] IDS = new String[50];
    private static final String[] MESSAGES = new String[64];

    private static com.sun.management.ThreadMXBean threads;

    @TempDir
    Path dir;

    /** Bytes allocated by the caller and by the worker (0 when not measured) in one round. */
    private record Round(long caller, long worker) {}

    @BeforeAll
    static void setUp() {
        assumeTrue(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean,
                "needs com.sun.management.ThreadMXBean");
        threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(threads.isThreadAllocatedMemorySupported(), "needs per-thread allocation counting");
        threads.setThreadAllocatedMemoryEnabled(true);

        for (int i = 0; i < IDS.length; i++) {
            IDS[i] = "user-" + i;
        }
        for (int i = 0; i < MESSAGES.length; i++) {
            MESSAGES[i] = "{\"seq\":" + i + ",\"ok\":" + (i % 2 == 0) + "}";
        }
    }

    /** Guards the build script: allocationTestNoEA must really run with escape analysis off. */
    @Test
    void runsWithTheEscapeAnalysisSettingItsTaskAskedFor() {
        String expected = System.getProperty("efficientlogger.escapeAnalysis");
        assumeTrue(expected != null, "not run by one of the Gradle allocation test tasks");
        HotSpotDiagnosticMXBean hotSpot = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        assertEquals(expected, hotSpot.getVMOption("DoEscapeAnalysis").getValue());
    }

    @Test
    void asyncLogAllocatesNothingOnTheCallerOrTheWorker() {
        try (EfficientLogger logger = EfficientLogger.builder(dir, "{id}.json", 1 << 20)
                .maxQueuedMessages(1 << 20) // room for a whole round, so nothing is dropped
                .maxQueuedChars(1L << 30)
                .workerPool(WorkerPool.create(1)) // a thread of its own, so what it allocates is this logger's
                .build()) {
            Thread worker = ((AsyncLogger) logger).thread(0);
            List<Round> rounds = measure(logger, CALLS, CALLS, worker, true);

            assertWithin(CALLER_ALLOWANCE, rounds, false, CALLS, "log() on the caller");
            assertWithin(WORKER_ALLOWANCE, rounds, true, CALLS, "the worker writing the messages");
            assertEquals(0, logger.droppedCount());
            assertEquals((long) (WARMUP_ROUNDS + MEASURED_ROUNDS) * CALLS, logger.stats().written());
        }
    }

    @Test
    void asyncLogAllocatesNothingWhenDropping() {
        CountDownLatch release = new CountDownLatch(1);
        TestHooks hooks = new TestHooks();
        hooks.beforeBatch = () -> awaitUninterruptibly(release); // the worker takes nothing, so the queue stays full
        EfficientLogger logger = EfficientLogger.builder(dir, "{id}.json", 1 << 20)
                .maxQueuedMessages(1024)
                .warningHandler((message, cause) -> {})
                .build(hooks);
        try {
            assertEquals(1024, logMany(logger, 1024));
            List<Round> rounds = measure(logger, CALLS, 0, null, false);
            assertWithin(CALLER_ALLOWANCE, rounds, false, CALLS, "log() dropping messages");
        } finally {
            release.countDown();
            logger.close();
        }
    }

    /** The rate cap is a CAS and the disk mark a volatile read on the accepted path, so nothing changes for the caller. */
    @Test
    void asyncLogAllocatesNothingWithTheRateCapAndDiskMarkOn() {
        try (EfficientLogger logger = EfficientLogger.builder(dir, "{id}.json", 1 << 20)
                .maxQueuedMessages(1 << 20)
                .maxQueuedChars(1L << 30)
                .maxCharsPerSecond(1L << 50) // never reached
                .minFreeDiskSpace(1, 1) // never below: the volume has more than a byte free
                .workerPool(WorkerPool.create(1))
                .build()) {
            // The worker isn't measured here: its once-a-second free-space reading allocates a few bytes by design.
            List<Round> rounds = measure(logger, CALLS, CALLS, null, true);
            assertWithin(CALLER_ALLOWANCE, rounds, false, CALLS, "log() with the rate cap and disk mark on");
            assertEquals(0, logger.droppedCount());
        }
    }

    @Test
    void asyncLogAllocatesNothingWhenSheddingForDiskSpace() {
        TestHooks hooks = new TestHooks();
        hooks.freeSpace = () -> 0; // a full volume
        try (EfficientLogger logger = EfficientLogger.builder(dir, "{id}.json", 1 << 20)
                .minFreeDiskSpace(1 << 20, 2 << 20)
                .warningHandler((message, cause) -> {})
                .build(hooks)) {
            List<Round> rounds = measure(logger, CALLS, 0, null, false);
            assertWithin(CALLER_ALLOWANCE, rounds, false, CALLS, "log() shedding for disk space");
            assertEquals((long) (WARMUP_ROUNDS + MEASURED_ROUNDS) * CALLS, logger.stats().droppedDiskSpace());
        }
    }

    @Test
    void asyncLogAllocatesNothingWhenOverTheRateCap() {
        try (EfficientLogger logger = EfficientLogger.builder(dir, "{id}.json", 1 << 20)
                .maxCharsPerSecond(1)
                .warningHandler((message, cause) -> {})
                .build()) {
            assertTrue(logger.log("a", "x".repeat(100_000)), "accepted while calm; at a char a second it is charged a day");
            List<Round> rounds = measure(logger, CALLS, 0, null, false);
            assertWithin(CALLER_ALLOWANCE, rounds, false, CALLS, "log() over the rate cap");
            assertEquals((long) (WARMUP_ROUNDS + MEASURED_ROUNDS) * CALLS, logger.stats().droppedRateLimit());
        }
    }

    @Test
    void logAfterCloseAllocatesNothing() {
        EfficientLogger logger = EfficientLogger.builder(dir, "{id}.json", 1 << 20).build();
        assertTrue(logger.close(Duration.ofSeconds(10)));
        List<Round> rounds = measure(logger, CALLS, 0, null, false);
        assertWithin(CALLER_ALLOWANCE, rounds, false, CALLS, "log() after close");
    }

    /** Each call writes to a file, so fewer calls; an allocation in each would still be megabytes. */
    @Test
    void syncLogAllocatesNothing() {
        int calls = 100_000;
        try (EfficientLogger logger = EfficientLogger.builder(dir, "{id}.json", 1 << 20).synchronous().build()) {
            List<Round> rounds = measure(logger, calls, calls, null, false);
            assertWithin(CALLER_ALLOWANCE, rounds, false, calls, "log() in sync mode");
        }
    }

    // ---- helpers ----

    /** Runs the warm-up rounds, then returns what the measured rounds allocated. */
    private static List<Round> measure(EfficientLogger logger, int calls, int accepted, Thread worker, boolean flush) {
        long a = threads.getCurrentThreadAllocatedBytes();
        long overhead = threads.getCurrentThreadAllocatedBytes() - a; // what reading the counter costs
        List<Round> measured = new ArrayList<>();
        for (int round = 0; round < WARMUP_ROUNDS + MEASURED_ROUNDS; round++) {
            long workerBefore = worker == null ? 0 : threads.getThreadAllocatedBytes(worker.getId());
            long before = threads.getCurrentThreadAllocatedBytes();
            int n = logMany(logger, calls);
            long caller = threads.getCurrentThreadAllocatedBytes() - before - overhead;
            assertEquals(accepted, n);
            if (flush) {
                assertTrue(logger.flush()); // the worker has written the round out
            }
            long workerBytes = worker == null ? 0 : threads.getThreadAllocatedBytes(worker.getId()) - workerBefore;
            if (round >= WARMUP_ROUNDS) {
                measured.add(new Round(caller, workerBytes));
            }
        }
        return measured;
    }

    /** The loop being measured. It allocates nothing itself. */
    private static int logMany(EfficientLogger logger, int calls) {
        String[] ids = IDS;
        String[] messages = MESSAGES;
        int accepted = 0;
        int id = 0;
        for (int i = 0; i < calls; i++) {
            if (logger.log(ids[id], messages[i & 63])) {
                accepted++;
            }
            if (++id == ids.length) {
                id = 0;
            }
        }
        return accepted;
    }

    private static void assertWithin(long allowance, List<Round> rounds, boolean worker, int calls, String what) {
        long best = Long.MAX_VALUE;
        List<Long> all = new ArrayList<>();
        for (Round r : rounds) {
            long bytes = worker ? r.worker() : r.caller();
            all.add(bytes);
            best = Math.min(best, bytes);
        }
        assertTrue(best <= allowance, what + " allocated " + all + " bytes in rounds of " + calls
                + " calls; allowed: " + allowance + " in at least one round");
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
