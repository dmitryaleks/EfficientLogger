package org.example.efficientlogger;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
    private final long startIndex;
    private final Duration closeTimeout;
    private final AtomicLong tooLarge = new AtomicLong();
    private final AtomicLong rejectedClosed = new AtomicLong();
    private final Thread shutdownHook;
    private final AtomicBoolean shutdownHookRemoved = new AtomicBoolean();

    AsyncLogger(Config config, TestHooks hooks) {
        int number = LOGGER_NUMBERS.incrementAndGet();
        warnings = new Warnings(config.warningHandler);
        budget = new OpenFileBudget(config.maxOpenFiles);
        admission = new Admission(config.maxQueuedMessages, config.maxQueuedChars);
        tracker = new OverflowTracker(admission, config, warnings);
        maxChars = config.maxQueuedChars;
        startIndex = hooks.ringStartIndex;
        closeTimeout = config.closeTimeout;

        int n = config.workerThreads;
        int batchSize = hooks.batchSize > 0 ? hooks.batchSize : config.batchSize;
        rings = new MpscRing[n];
        workers = new Worker[n];
        for (int i = 0; i < n; i++) {
            rings[i] = new MpscRing(config.ringCapacity, startIndex, config.wakeCount, config.wakeChars);
            FileTable files = new FileTable(config.fileNamePattern, config.lineSeparator, config.fileBufferBytes,
                    config.maxOpenFiles + FileTable.EXTRA_ENTRIES, hooks.sinks, budget, warnings);
            workers[i] = new Worker(rings[i], files, admission, tracker, warnings, hooks.nanoClock, batchSize,
                    config.flushIntervalNanos, config.eagerWakeup);
        }

        if (!hooks.startThreads) {
            threads = null;
            shutdownHook = null;
            return;
        }
        threads = startThreads(config.threadFactory != null ? config.threadFactory : defaultThreadFactory(number));
        shutdownHook = config.registerShutdownHook ? registerShutdownHook(number) : null;
    }

    @Override
    public boolean log(String id, String json) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(json, "json");
        int len = json.length();
        if (len > maxChars) {
            tracker.recordDrop(); // before tooLarge, so stats() never sees more too-large than drops
            tooLarge.incrementAndGet();
            return false;
        }
        MpscRing ring = rings[Hashing.index(id.hashCode(), rings.length)];
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
        return tracker.drops();
    }

    @Override
    public LoggerStats stats() {
        long accepted = 0;
        for (MpscRing ring : rings) {
            accepted += ring.tail() - startIndex;
        }
        long written = 0;
        long invalidId = 0;
        long lostIoError = 0;
        for (Worker w : workers) {
            written += w.files.written();
            invalidId += w.files.droppedInvalidId();
            lostIoError += w.files.lostIoError();
        }
        long large = tooLarge.get(); // before drops(): see log()
        long drops = tracker.drops();
        long word = admission.get();
        return new LoggerStats(accepted, written, drops - large, large, invalidId, lostIoError, rejectedClosed.get(),
                Admission.messages(word), Admission.chars(word), budget.open());
    }

    @Override
    public boolean close(Duration timeout) {
        long timeoutNanos = saturatedNanos(timeout);
        long start = System.nanoTime();
        admission.close(); // from here on log() returns false; idempotent
        if (threads == null) {
            return allTerminated();
        }
        for (MpscRing ring : rings) {
            ring.wakeConsumer();
        }
        boolean finished = true;
        boolean fromWorker = false;
        for (Thread t : threads) {
            if (t == Thread.currentThread()) {
                fromWorker = true; // e.g. from a warning handler: it finishes after returning
                finished = false;
                continue;
            }
            long remaining = timeoutNanos - (System.nanoTime() - start);
            try {
                if (remaining > 0) {
                    TimeUnit.NANOSECONDS.timedJoin(t, remaining);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                finished = false;
                break;
            }
            if (t.isAlive()) {
                finished = false;
            }
        }
        removeShutdownHook();
        if (!finished && !fromWorker) {
            warnings.emit("close didn't finish within " + TimeUnit.NANOSECONDS.toMillis(timeoutNanos) + " ms; "
                    + Admission.messages(admission.get()) + " messages are still queued and will be written in the"
                    + " background", null);
        }
        return finished;
    }

    @Override
    public void close() {
        close(closeTimeout);
    }

    /** For tests that run the workers by hand. */
    Worker worker(int i) {
        return workers[i];
    }

    int workerCount() {
        return workers.length;
    }

    // ---- threads ----

    private Thread[] startThreads(ThreadFactory factory) {
        Thread[] created = new Thread[workers.length];
        int started = 0;
        try {
            for (int i = 0; i < workers.length; i++) {
                Thread t = factory.newThread(workers[i]);
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

    private Thread registerShutdownHook(int loggerNumber) {
        Thread hook = new Thread(() -> close(), "efficient-logger-" + loggerNumber + "-shutdown");
        try {
            Runtime.getRuntime().addShutdownHook(hook);
            return hook;
        } catch (IllegalStateException e) {
            return null; // the JVM is already shutting down
        }
    }

    private void removeShutdownHook() {
        if (shutdownHook != null && shutdownHookRemoved.compareAndSet(false, true)) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
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
