package org.example.efficientlogger;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The logger-wide soft cap on open files, shared by all its {@link FileTable}s. Touched only when
 * a file opens or closes.
 *
 * <p>Soft: a table that goes over the cap closes its own least recently used files, but if it has
 * none open, it stays over rather than reach into another thread's table.
 */
final class OpenFileBudget {

    private final int max;
    private final AtomicInteger open = new AtomicInteger();

    OpenFileBudget(int max) {
        this.max = max;
    }

    /** Takes a slot. True if that puts the count over the cap. */
    boolean acquire() {
        return open.incrementAndGet() > max;
    }

    boolean isOver() {
        return open.get() > max;
    }

    void release() {
        open.decrementAndGet();
    }

    int open() {
        return open.get();
    }

    int max() {
        return max;
    }
}
