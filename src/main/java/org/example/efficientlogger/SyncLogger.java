package org.example.efficientlogger;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Synchronous mode: {@code log} writes on the calling thread, with no queue and no extra threads.
 *
 * <p>IDs are spread over stripes, each with its own lock and {@link FileTable}. A given ID always
 * uses the same stripe, so its lines are written in lock order. Different stripes don't block
 * each other. Warnings are emitted after the lock is released, so a slow handler holds up only its
 * own caller, and a handler may call {@code log} or {@code close} itself.
 */
final class SyncLogger implements EfficientLogger {

    private static final class Stripe {
        final FileTable files;
        boolean closed; // guarded by this

        Stripe(FileTable files) {
            this.files = files;
        }
    }

    private final Stripe[] stripes;
    private final Duration closeTimeout;
    private final Warnings warnings;
    private final OpenFileBudget budget;
    private final LongSupplier clock;
    private final int maxIdLength;
    private final AtomicLong rejectedClosed = new AtomicLong();
    private volatile boolean closed;

    SyncLogger(Config config, TestHooks hooks) {
        warnings = new Warnings(config.warningHandler, config.maxIdLength);
        maxIdLength = config.maxIdLength;
        String exposed = hooks.sinks.directoryExposedReason(config.directory);
        if (exposed != null) {
            warnings.emit(exposed, null);
        }
        if (config.filePermissions != null && !FileOutputStreamSink.posixSupported()) {
            warnings.emit("filePermissions is set but this file system has no POSIX permissions;"
                    + " new files follow the directory's access rules instead", null);
        }
        budget = new OpenFileBudget(config.maxOpenFiles);
        FileCreationBudget fileBudget = new FileCreationBudget(config.maxFiles);
        clock = hooks.nanoClock;
        closeTimeout = config.closeTimeout;
        stripes = new Stripe[config.syncStripes];
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new Stripe(new FileTable(config.fileNamePattern, config.lineSeparator,
                    config.fileBufferBytes, config.maxFileEntries, hooks.sinks,
                    budget, fileBudget, warnings));
        }
    }

    @Override
    public boolean log(String id, String json) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(json, "json");
        if (id.length() > maxIdLength) {
            // It can't fit in a file name: reject it before taking a lock or copying it.
            warnings.countTooLongId();
            warnings.reportTooLongIds(); // outside any lock, like sync mode's other warnings
            return false;
        }
        if (closed) {
            rejectedClosed.incrementAndGet();
            return false;
        }
        Stripe s = stripes[Hashing.index(id.hashCode(), stripes.length)];
        boolean written;
        List<Warnings.Pending> pending;
        synchronized (s) {
            if (s.closed) {
                rejectedClosed.incrementAndGet();
                return false;
            }
            written = s.files.writeThrough(id, json, clock.getAsLong());
            pending = s.files.takeWarnings();
        }
        if (pending != null) {
            warnings.emitAll(pending);
        }
        return written;
    }

    @Override
    public boolean flush(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        return true; // log() already wrote everything
    }

    @Override
    public boolean flush() {
        return true;
    }

    @Override
    public long droppedCount() {
        return 0; // no queue, so nothing is dropped for space
    }

    @Override
    public LoggerStats stats() {
        long written = 0;
        long invalidId = warnings.tooLongIds();
        long lostIoError = 0;
        for (Stripe s : stripes) {
            written += s.files.written();
            invalidId += s.files.droppedInvalidId();
            lostIoError += s.files.lostIoError();
        }
        return new LoggerStats(written + invalidId + lostIoError, written, 0, 0, invalidId, lostIoError,
                rejectedClosed.get(), 0, 0, budget.open());
    }

    @Override
    public boolean close(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        closed = true;
        for (Stripe s : stripes) {
            List<Warnings.Pending> pending;
            synchronized (s) { // waits for a write in progress on this stripe
                if (!s.closed) {
                    s.closed = true;
                    s.files.closeAll(clock.getAsLong());
                }
                pending = s.files.takeWarnings();
            }
            warnings.emitAll(pending);
        }
        warnings.reportSuppressedIoErrors();
        return true;
    }

    @Override
    public void close() {
        close(closeTimeout);
    }
}
