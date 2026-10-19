package org.example.efficientlogger;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Asynchronous mode: {@code log} admits the message against the queue limits and puts it in the
 * ring of the worker that owns its ID. That is one CAS, one atomic add, two reference stores and one
 * volatile read, with no allocation and no locks. Everything else happens on the workers, which run
 * on the threads of a {@link WorkerPool} shared with other loggers.
 *
 * <p>Built in two steps: the constructor creates everything without handing it to any thread, and
 * {@link #start} registers the workers with the pool once construction is complete, so no pool
 * thread can ever observe a half-built logger.
 */
final class AsyncLogger implements EfficientLogger {

    private final MpscRing[] rings;
    private final Worker[] workers;
    private final WorkerPool pool; // null when tests run the workers by hand
    private final boolean registerShutdownHook;
    private final Admission admission;
    private final OverflowTracker tracker;
    private final DiskWatch disk; // null unless minFreeDiskSpace is set
    private final RateLimit rate; // null unless maxCharsPerSecond is set
    private final LongSupplier clock;
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
    private final AtomicInteger running; // workers that haven't finished
    private final CountDownLatch finished; // counted down as each worker finishes

    AsyncLogger(Config config, TestHooks hooks) {
        warnings = new Warnings(config.warningHandler, config.maxIdLength, config.maxQueuedChars);
        budget = new OpenFileBudget(config.maxOpenFiles);
        FileCreationBudget fileBudget = new FileCreationBudget(config.maxFiles);
        admission = new Admission(config.maxQueuedMessages, config.maxQueuedChars);
        tracker = new OverflowTracker(admission, config, warnings);
        clock = hooks.nanoClock;
        long now = clock.getAsLong();
        disk = config.minFreeDiskBytes > 0
                ? new DiskWatch(hooks.freeSpace != null ? hooks.freeSpace : DiskWatch.forDirectory(config.directory),
                        config.minFreeDiskBytes, config.resumeFreeDiskBytes, warnings, now)
                : null;
        rate = config.maxCharsPerSecond > 0 ? new RateLimit(config.maxCharsPerSecond, warnings, now) : null;
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
                    config.maxFileEntries, config.maxFileBytes, hooks.sinks, budget, fileBudget, warnings);
            workers[i] = new Worker(rings[i], files, admission, tracker, disk, rate, warnings, hooks.nanoClock, batchSize,
                    config.flushIntervalNanos, config.eagerWakeup, hooks.beforeBatch);
            workers[i].onTerminated(this::workerFinished);
        }

        running = new AtomicInteger(n);
        finished = new CountDownLatch(n);
        registerShutdownHook = config.registerShutdownHook;
        pool = hooks.startThreads ? (config.workerPool != null ? config.workerPool : WorkerPool.shared()) : null;
    }

    /**
     * Hands the workers to the pool and registers the shutdown hook. The builder calls this once
     * construction has finished; the constructor itself hands {@code this} to nothing that runs.
     *
     * @return this
     */
    AsyncLogger start() {
        if (pool == null) {
            return this; // tests run the workers by hand
        }
        try {
            pool.register(workers);
        } catch (RuntimeException | Error e) {
            admission.close(); // nothing will ever drain the rings
            throw e;
        }
        if (registerShutdownHook) {
            try {
                ShutdownHook.register(this);
            } catch (RuntimeException | Error e) {
                beginClose(); // the workers are running: have them finish and leave the pool, since nobody will hold this logger
                throw e;
            }
        }
        return this;
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
            if (closing()) {
                return rejectClosed(); // after close it is only that, and no worker is left to warn
            }
            if (warnings.countOversizeMessage()) {
                rings[0].wakeConsumer();
            }
            return false;
        }
        if (disk != null && disk.shedding()) {
            if (closing()) {
                return rejectClosed(); // after close it is only that
            }
            disk.recordDrop();
            if (disk.shouldWake(clock.getAsLong())) {
                rings[0].wakeConsumer(); // a worker takes the overdue reading, so shedding ends even if they are all asleep
            }
            return false;
        }
        MpscRing ring = rings[Hashing.bucket(id, shardKey, rings.length)];
        long admitted = admission.tryAcquire(len);
        if (admitted <= 0) {
            if (admitted == Admission.REJECTED_FULL) {
                tracker.recordDrop();
                return false;
            }
            return rejectClosed();
        }
        if (rate != null) {
            // After admission, so a message the queue refuses (or a closed logger) is never charged to the rate.
            long now = clock.getAsLong();
            if (!rate.tryAcquire(len, now)) {
                admission.release(Admission.delta(len)); // it won't be queued after all
                rate.recordDrop(now);
                return false;
            }
        }
        ring.offer(id, json, admitted); // can't fail or throw, so the admitted slot is always published
        return true;
    }

    /** Counts a message whose ID is too long for a file name. Allocates nothing. */
    private boolean rejectTooLongId() {
        if (closing()) {
            return rejectClosed(); // after close it is only that, and no worker is left to warn
        }
        if (warnings.countTooLongId()) {
            rings[0].wakeConsumer(); // a worker reports it, so the caller does no I/O
        }
        return false;
    }

    private boolean rejectClosed() {
        rejectedClosed.incrementAndGet();
        return false;
    }

    @Override
    public boolean flush(Duration timeout) {
        long deadline = System.nanoTime() + saturatedNanos(timeout);
        if (pool == null || onOwnThread()) {
            return false; // nobody to wait for, or a worker's thread waiting for itself
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
        return tracker.drops() + warnings.oversizeMessages() + (disk != null ? disk.drops() : 0) + (rate != null ? rate.drops() : 0);
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
        long fileFull = 0;
        long lostIoError = 0;
        for (Worker w : workers) {
            written += w.files.written();
            invalidId += w.files.droppedInvalidId();
            fileFull += w.files.droppedFileFull();
            lostIoError += w.files.lostIoError();
        }
        long oversize = warnings.oversizeMessages();
        long queueFull = tracker.drops();
        long word = admission.get();
        return new LoggerStats(accepted, written, queueFull, oversize, invalidId, fileFull,
                disk != null ? disk.drops() : 0, rate != null ? rate.drops() : 0, lostIoError, abandoned,
                rejectedClosed.get(), Admission.messages(word), Admission.chars(word), budget.open());
    }

    @Override
    public boolean close(Duration timeout) {
        long timeoutNanos = saturatedNanos(timeout);
        beginClose();
        if (pool == null) {
            return allTerminated() && nothingLeft();
        }
        if (onOwnThread()) {
            // E.g. from a warning handler. The drain needs this very thread, so don't wait for it:
            // it carries on once the handler returns.
            return false;
        }
        return awaitClose(timeoutNanos, timeoutNanos, false);
    }

    @Override
    public void close() {
        close(closeTimeout);
    }

    /** Stops accepting messages and wakes the workers' threads. Idempotent. */
    void beginClose() {
        admission.close(); // from here on log() returns false
        for (MpscRing ring : rings) {
            ring.wakeConsumer();
        }
    }

    /**
     * The shutdown hook's wait for this logger, after {@link #beginClose}: until the logger's close
     * timeout, counted from when the hook started closing every logger (they all drain at once).
     */
    void awaitCloseAtExit(long hookStartNanos) {
        long timeoutNanos = saturatedNanos(closeTimeout);
        long remaining = Math.max(0, timeoutNanos - (System.nanoTime() - hookStartNanos));
        awaitClose(remaining, timeoutNanos, true);
    }

    /**
     * @param waitNanos how long to wait for the workers to finish
     * @param timeoutNanos the timeout to report if they don't
     * @param jvmExiting true when called from the shutdown hook: a timeout then means the messages
     *     are lost, because the JVM halts once the hooks return, rather than written in the background
     */
    private boolean awaitClose(long waitNanos, long timeoutNanos, boolean jvmExiting) {
        boolean done;
        try {
            done = finished.await(waitNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (!done) {
            String fate = jvmExiting
                    ? "won't be written: the JVM is exiting"
                    : "will be written in the background";
            warnings.emit("close didn't finish within " + TimeUnit.NANOSECONDS.toMillis(timeoutNanos)
                    + " ms; the remaining " + messages(unwritten()) + " " + fate, null);
            return false;
        }
        // The workers give up on a queue that stops draining (see Worker), so "finished" doesn't
        // mean "everything written": say so, as the javadoc promises.
        return nothingLeft();
    }

    /**
     * Messages not yet handed to the OS: still queued, or taken from a queue into a file buffer
     * that a worker hasn't written out (for example because its thread is stalled in a write).
     * A snapshot of moving counters: a record inside the batch being drained can be in both for
     * an instant.
     */
    private long unwritten() {
        long n = Admission.messages(admission.get());
        for (Worker w : workers) {
            n += w.files.buffered();
        }
        return n;
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

    /** For tests: the pool thread serving worker i, or null when the workers run by hand. */
    Thread thread(int i) {
        return workers[i].thread();
    }

    /** For tests that play the part of a producer step by step. */
    Admission admission() {
        return admission;
    }

    /** True once close has started. */
    boolean closing() {
        return Admission.isClosed(admission.get());
    }

    /** For tests: the shutdown hook that will close this logger, or null if there is none (any more). */
    Thread shutdownHook() {
        return ShutdownHook.hookFor(this);
    }

    // ---- the workers' threads ----

    /**
     * Runs on the pool thread once a worker has finished. The last one removes this logger from
     * the shutdown hook, which no longer needs it, and reports any messages the workers gave up
     * on, before close() is released.
     */
    private void workerFinished() {
        try {
            if (running.decrementAndGet() == 0) {
                ShutdownHook.unregister(this);
                reportLeftBehind();
            }
        } finally {
            finished.countDown();
        }
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

    /**
     * True if the current thread is a pool thread serving one of this logger's workers that hasn't
     * finished: waiting on it would wait for ourselves. (Once all have finished, any thread may wait:
     * the wait returns at once.)
     */
    private boolean onOwnThread() {
        if (finished.getCount() == 0) {
            return false;
        }
        Thread current = Thread.currentThread();
        for (Worker w : workers) {
            if (w.thread() == current) {
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
