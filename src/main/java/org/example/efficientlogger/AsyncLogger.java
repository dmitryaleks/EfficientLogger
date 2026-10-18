package org.example.efficientlogger;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Asynchronous mode: {@code log} admits the message against the queue limits and puts it in the
 * ring of the worker that owns its ID. That is one CAS, one atomic add, two reference stores and one
 * volatile read, with no allocation and no locks. Everything else happens on the workers.
 */
final class AsyncLogger implements EfficientLogger {

    private static final AtomicInteger LOGGER_NUMBERS = new AtomicInteger();

    private final MpscRing[] rings;
    private final Worker[] workers;
    private final Thread[] threads; // null when tests run the workers by hand
    private final Admission admission;
    private final OverflowTracker tracker;
    private final Warnings warnings;
    private final OpenFileBudget budget;
    private final long maxChars;
    private final int maxIdLength;
    private final int maxIdLengthBeforeNormalizing;
    private final boolean normalizeIds;
    private final long shardKey;
    private final long startIndex;
    private final Duration closeTimeout;
    private final AtomicLong rejectedClosed = new AtomicLong();
    private final AtomicInteger running; // worker threads that haven't finished
    private final CountDownLatch finished; // counted down as each worker thread finishes
    private final AtomicReference<Thread> shutdownHook = new AtomicReference<>(); // null once removed

    AsyncLogger(Config config, TestHooks hooks) {
        int number = LOGGER_NUMBERS.incrementAndGet();
        warnings = new Warnings(config.warningHandler, config.maxIdLength, config.maxQueuedChars);
        budget = new OpenFileBudget(config.maxOpenFiles);
        FileCreationBudget fileBudget = new FileCreationBudget(config.maxFiles);
        admission = new Admission(config.maxQueuedMessages, config.maxQueuedChars);
        tracker = new OverflowTracker(admission, config, warnings);
        maxChars = config.maxQueuedChars;
        maxIdLength = config.maxIdLength;
        maxIdLengthBeforeNormalizing = config.maxIdLengthBeforeNormalizing;
        normalizeIds = config.normalizeIds;
        String exposed = hooks.sinks.directoryExposedReason(config.directory);
        if (exposed != null) {
            warnings.emit(exposed, null);
        }
        if (config.filePermissions != null && !FileOutputStreamSink.posixSupported()) {
            warnings.emit("filePermissions is set but this file system has no POSIX permissions;"
                    + " new files follow the directory's access rules instead", null);
        }
        shardKey = hooks.shardKey;
        startIndex = hooks.ringStartIndex;
        closeTimeout = config.closeTimeout;

        int n = config.workerThreads;
        int batchSize = hooks.batchSize > 0 ? hooks.batchSize : config.batchSize;
        rings = new MpscRing[n];
        workers = new Worker[n];
        for (int i = 0; i < n; i++) {
            rings[i] = new MpscRing(config.ringCapacity, startIndex, config.wakeCount, config.wakeChars);
            FileTable files = new FileTable(config.fileNamePattern, config.lineSeparator, config.fileBufferBytes,
                    config.maxFileEntries, hooks.sinks, budget, fileBudget, warnings);
            workers[i] = new Worker(rings[i], files, admission, tracker, warnings, hooks.nanoClock, batchSize,
                    config.flushIntervalNanos, config.eagerWakeup, hooks.beforeBatch);
        }

        running = new AtomicInteger(n);
        finished = new CountDownLatch(n);
        if (!hooks.startThreads) {
            threads = null;
            return;
        }
        threads = startThreads(config.threadFactory != null ? config.threadFactory : defaultThreadFactory(number));
        if (config.registerShutdownHook) {
            registerShutdownHook(number);
        }
    }

    @Override
    public boolean log(String id, String json) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(json, "json");
        if (normalizeIds) {
            if (id.length() > maxIdLengthBeforeNormalizing) {
                return rejectTooLongId(); // it can't normalize to fit, so don't copy it to find out
            }
            id = FileNamePattern.normalize(id); // route and name by the canonical form
        }
        if (id.length() > maxIdLength) {
            // It can't fit in a file name. Rejecting it here keeps a huge ID out of the queue,
            // where maxQueuedChars doesn't count it.
            return rejectTooLongId();
        }
        int len = json.length();
        if (len > maxChars) {
            // Too large for the queue. Not a full queue, so it doesn't feed overflow episodes; it
            // has its own warning, which a worker emits so the caller does no I/O.
            if (warnings.countOversizeMessage()) {
                rings[0].wakeConsumer();
            }
            return false;
        }
        MpscRing ring = rings[Hashing.bucket(id, shardKey, rings.length)];
        long admitted = admission.tryAcquire(len);
        if (admitted <= 0) {
            if (admitted == Admission.REJECTED_FULL) {
                tracker.recordDrop();
            } else {
                rejectedClosed.incrementAndGet();
            }
            return false;
        }
        ring.offer(id, json, admitted); // can't fail or throw, so the admitted slot is always published
        return true;
    }

    /** Counts a message whose ID is too long for a file name. Allocates nothing. */
    private boolean rejectTooLongId() {
        if (warnings.countTooLongId()) {
            rings[0].wakeConsumer(); // a worker reports it, so the caller does no I/O
        }
        return false;
    }

    @Override
    public boolean flush(Duration timeout) {
        long deadline = System.nanoTime() + saturatedNanos(timeout);
        if (threads == null || isWorkerThread()) {
            return false; // nobody to wait for, or a worker waiting for itself
        }
        long[] tickets = new long[workers.length];
        for (int i = 0; i < workers.length; i++) {
            tickets[i] = workers[i].requestFlush();
        }
        try {
            for (int i = 0; i < workers.length; i++) {
                if (!workers[i].awaitFlush(tickets[i], deadline)) {
                    return false;
                }
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public boolean flush() {
        return flush(closeTimeout);
    }

    @Override
    public long droppedCount() {
        return tracker.drops() + warnings.oversizeMessages();
    }

    @Override
    public LoggerStats stats() {
        long tooLongIds = warnings.tooLongIds(); // counted as accepted and dropped for an invalid id
        long accepted = tooLongIds;
        long abandoned = 0;
        for (int i = 0; i < rings.length; i++) {
            long tail = rings[i].tail(); // read once, so accepted and abandoned agree on a late publish
            accepted += tail - startIndex;
            if (workers[i].terminated()) {
                // Claimed but never consumed: the worker gave up on them (terminated is volatile,
                // so its final head is visible here).
                abandoned += tail - rings[i].head();
            }
        }
        long written = 0;
        long invalidId = tooLongIds;
        long lostIoError = 0;
        for (Worker w : workers) {
            written += w.files.written();
            invalidId += w.files.droppedInvalidId();
            lostIoError += w.files.lostIoError();
        }
        long oversize = warnings.oversizeMessages();
        long queueFull = tracker.drops();
        long word = admission.get();
        return new LoggerStats(accepted, written, queueFull, oversize, invalidId, lostIoError, abandoned,
                rejectedClosed.get(), Admission.messages(word), Admission.chars(word), budget.open());
    }

    @Override
    public boolean close(Duration timeout) {
        return close(timeout, false);
    }

    @Override
    public void close() {
        close(closeTimeout, false);
    }

    /**
     * @param jvmExiting true when called from the shutdown hook: a timeout then means the messages
     *     are lost, because the JVM halts once the hooks return, rather than written in the background
     */
    private boolean close(Duration timeout, boolean jvmExiting) {
        long timeoutNanos = saturatedNanos(timeout);
        admission.close(); // from here on log() returns false; idempotent
        if (threads == null) {
            return allTerminated() && nothingLeft();
        }
        for (MpscRing ring : rings) {
            ring.wakeConsumer();
        }
        if (isWorkerThread()) {
            // E.g. from a warning handler. The drain needs this very thread, so don't wait for it:
            // it carries on once the handler returns.
            return false;
        }
        boolean done;
        try {
            done = finished.await(timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (!done) {
            String fate = jvmExiting
                    ? "won't be written: the JVM is exiting"
                    : "will be written in the background";
            warnings.emit("close didn't finish within " + TimeUnit.NANOSECONDS.toMillis(timeoutNanos)
                    + " ms; the remaining " + messages(Admission.messages(admission.get())) + " " + fate, null);
            return false;
        }
        // The workers give up on a queue that stops draining (see Worker), so "finished" doesn't
        // mean "everything written": say so, as the javadoc promises.
        return nothingLeft();
    }

    /** True if every admitted message has been consumed. Only meaningful once the workers have finished. */
    private boolean nothingLeft() {
        return Admission.messages(admission.get()) == 0;
    }

    /** For tests that run the workers by hand. */
    Worker worker(int i) {
        return workers[i];
    }

    int workerCount() {
        return workers.length;
    }

    /** For tests: worker thread i, or null when the workers run by hand. */
    Thread thread(int i) {
        return threads == null ? null : threads[i];
    }

    /** For tests that play the part of a producer step by step. */
    Admission admission() {
        return admission;
    }

    /** For tests: true once close has started. */
    boolean closing() {
        return Admission.isClosed(admission.get());
    }

    /** For tests: the registered shutdown hook, or null if there is none (any more). */
    Thread shutdownHook() {
        return shutdownHook.get();
    }

    // ---- threads ----

    /**
     * What a worker thread runs. The last one to finish removes the shutdown hook, which is no
     * longer needed, and reports any messages the workers gave up on, before close() is released.
     */
    private Runnable task(Worker worker) {
        return () -> {
            try {
                worker.run();
            } catch (Throwable t) {
                // worker.run() guards its own loop, so this is a last resort. Don't leave the logger
                // accepting messages that no worker will write.
                if (!Admission.isClosed(admission.get())) {
                    admission.close();
                    warnings.emit("a logging worker stopped unexpectedly; the logger is now closed", t);
                }
            } finally {
                if (running.decrementAndGet() == 0) {
                    removeShutdownHook();
                    reportLeftBehind();
                }
                finished.countDown();
            }
        };
    }

    /**
     * Once every worker has finished: the admitted messages that were never consumed. The workers
     * only stop with some left when the queue made no progress for {@link Worker#STUCK_CLOSE_NANOS},
     * which a {@code log()} call cut off (or stalled) between admission and publishing causes.
     */
    private void reportLeftBehind() {
        long left = Admission.messages(admission.get());
        if (left == 0) {
            return;
        }
        boolean one = left == 1;
        warnings.emit("close stopped waiting: " + messages(left) + (one ? " was" : " were") + " still queued after "
                + TimeUnit.NANOSECONDS.toSeconds(Worker.STUCK_CLOSE_NANOS) + " s without progress, because a log()"
                + " call was cut off (for example by a StackOverflowError) or stalled between being admitted and"
                + " queueing its message. " + (one ? "It" : "They") + " won't be written; see stats().queuedMessages()"
                + " and abandoned()", null);
    }

    private Thread[] startThreads(ThreadFactory factory) {
        Thread[] created = new Thread[workers.length];
        int started = 0;
        try {
            for (int i = 0; i < workers.length; i++) {
                Thread t = factory.newThread(task(workers[i]));
                if (t == null) {
                    throw new IllegalStateException("threadFactory returned null");
                }
                created[i] = t;
                rings[i].attachConsumer(t);
            }
            for (; started < created.length; started++) {
                created[started].start();
            }
            return created;
        } catch (RuntimeException | Error e) {
            // Don't leave the started workers running: they stop at once, since nothing is queued.
            admission.close();
            for (int i = 0; i < started; i++) {
                rings[i].wakeConsumer();
                try {
                    created[i].join(TimeUnit.SECONDS.toMillis(10));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            throw e;
        }
    }

    private static ThreadFactory defaultThreadFactory(int loggerNumber) {
        AtomicInteger next = new AtomicInteger();
        return runnable -> {
            Thread t = new Thread(runnable, "efficient-logger-" + loggerNumber + "-worker-" + next.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }

    private void registerShutdownHook(int loggerNumber) {
        Thread hook = new Thread(() -> close(closeTimeout, true), "efficient-logger-" + loggerNumber + "-shutdown");
        try {
            Runtime.getRuntime().addShutdownHook(hook);
            shutdownHook.set(hook);
        } catch (IllegalStateException e) {
            // the JVM is already shutting down
        }
    }

    private void removeShutdownHook() {
        Thread hook = shutdownHook.getAndSet(null);
        if (hook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException e) {
                // the JVM is shutting down, possibly running this very hook
            }
        }
    }

    private boolean isWorkerThread() {
        for (Thread t : threads) {
            if (t == Thread.currentThread()) {
                return true;
            }
        }
        return false;
    }

    private boolean allTerminated() {
        for (Worker w : workers) {
            if (!w.terminated()) {
                return false;
            }
        }
        return true;
    }

    private static String messages(long n) {
        return n == 1 ? "1 message" : n + " messages";
    }

    private static long saturatedNanos(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            return 0;
        }
        try {
            return timeout.toNanos();
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }
}
