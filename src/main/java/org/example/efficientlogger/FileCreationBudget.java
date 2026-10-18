package org.example.efficientlogger;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The logger-wide cap on how many new files it will create, shared by all its {@link FileTable}s.
 * Guards against untrusted IDs filling the disk or the directory with files.
 *
 * <p>It counts files the logger actually creates, not reopens: a file that already exists (so a
 * message for a known ID, even one evicted from a table and reopened) doesn't count, so a bounded
 * set of IDs never hits the cap however long the logger runs. Once {@code maxFiles} new files have
 * been created, {@link #tryCreate} returns false and the message is dropped. The count never falls,
 * so the cap bounds the files created over a run.
 */
final class FileCreationBudget {

    static final int UNLIMITED = Integer.MAX_VALUE;

    private final int max;
    private final AtomicInteger created = new AtomicInteger();

    FileCreationBudget(int max) {
        this.max = max;
    }

    /** Reserves one new file. False if that would exceed {@code maxFiles}; then nothing is reserved. */
    boolean tryCreate() {
        if (max == UNLIMITED) {
            return true; // no cap: never touch the shared counter
        }
        if (created.incrementAndGet() > max) {
            created.decrementAndGet();
            return false;
        }
        return true;
    }

    int max() {
        return max;
    }
}
