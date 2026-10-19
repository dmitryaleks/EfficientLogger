package org.example.efficientlogger;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The process-wide cap on how many new files loggers create ({@code maxFiles}), shared by every
 * logger (see {@link ProcessLimits}). Guards against untrusted IDs filling the disk or a directory
 * with files.
 *
 * <p>It counts files the loggers actually create, not reopens: a file that already exists (so a
 * message for a known ID, even one evicted from a table and reopened) doesn't count, so a bounded
 * set of IDs never hits the cap however long the process runs. Once {@code maxFiles} new files have
 * been created, {@link #tryCreate} returns false and the message is dropped. The count only falls
 * when a reserved file turns out not to have been created ({@link #release}), so the cap bounds the
 * files created over a run. The cap itself can change: the latest {@code build()} that sets
 * {@code maxFiles} wins, the count carries on, and a cap below the count caps every new ID until it
 * is raised again (a capped ID is tried again once a second).
 */
final class FileCreationBudget {

    static final int UNLIMITED = Integer.MAX_VALUE;

    private volatile int max;
    private final AtomicInteger created = new AtomicInteger();

    FileCreationBudget() {
        this(UNLIMITED);
    }

    FileCreationBudget(int max) {
        this.max = max;
    }

    /** Sets the cap; files already created stay counted. */
    void setMax(int max) {
        this.max = max;
    }

    /** Reserves one new file. False if that would exceed {@code maxFiles}; then nothing is reserved. */
    boolean tryCreate() {
        if (created.incrementAndGet() > max) {
            created.decrementAndGet();
            return false;
        }
        return true;
    }

    /** Gives back a reservation from {@link #tryCreate} whose file wasn't created after all. */
    void release() {
        created.decrementAndGet();
    }

    int max() {
        return max;
    }

    int created() {
        return created.get();
    }
}
