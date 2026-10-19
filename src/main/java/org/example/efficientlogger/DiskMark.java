package org.example.efficientlogger;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The process-wide low-water mark on free disk space ({@code minFreeDiskSpace}): the two
 * thresholds every logger shares, and one {@link Volume} per file store that loggers write to,
 * holding that volume's reading and whether it is shedding. The latest {@code build()} that sets
 * the mark changes the thresholds for every volume; until some build sets it, nothing is read or
 * shed.
 *
 * <p>Each volume is read at most once per {@link #CHECK_INTERVAL_NANOS}, by whichever thread calls
 * {@link Volume#check} first after the interval, so a hundred loggers on one disk take one reading
 * a second between them. The reading is one system call, which the JDK answers with a small
 * object; like a write, it can stall on a hung network volume. Between readings, {@code log()}
 * reads one volatile flag. If a volume can't be read, nothing is shed on it and the thread that
 * found out gets the failure to report, once per failure. Each logger counts and reports its own
 * shed messages through its {@link DiskWatch}.
 */
final class DiskMark {

    /** How a volume's free space is read; one system call. Tests supply their own. */
    interface Probe {
        long usableBytes() throws IOException;
    }

    static final long CHECK_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long UNSET = Long.MIN_VALUE;

    private volatile long shedBelow; // 0: the mark isn't set
    private volatile long resumeAbove;
    private final ConcurrentHashMap<Object, Volume> volumes = new ConcurrentHashMap<>(); // by FileStore, or by directory for tests

    /** Sets the thresholds for every volume. */
    void set(long shedBelow, long resumeAbove) {
        assert shedBelow >= 1 && resumeAbove >= shedBelow;
        this.resumeAbove = resumeAbove;
        this.shedBelow = shedBelow;
    }

    boolean enabled() {
        return shedBelow > 0;
    }

    long shedBelow() {
        return shedBelow;
    }

    long resumeAbove() {
        return resumeAbove;
    }

    /**
     * The volume holding {@code directory}. Loggers whose directories are on one file store share
     * it. A test passes its own probe and gets a volume keyed by the directory instead.
     */
    Volume volume(Path directory, Probe testProbe) {
        if (testProbe != null) {
            return volumes.computeIfAbsent(directory, k -> new Volume(testProbe));
        }
        FileStore store;
        try {
            store = Files.getFileStore(directory);
        } catch (IOException | RuntimeException e) {
            // No file store to share with: a volume of this directory's own, whose readings fail
            // (and are reported once) until the mark is turned off or the directory comes back.
            return volumes.computeIfAbsent(directory, k -> new Volume(() -> {
                throw new IOException("can't find the file store of " + directory, e);
            }));
        }
        return volumes.computeIfAbsent(store, k -> new Volume(store::getUsableSpace));
    }

    /** One file store's free-space reading and shedding state, shared by the loggers on it. */
    final class Volume {
        private final Probe probe;
        private final AtomicLong nextCheckNanos = new AtomicLong(UNSET);
        private final AtomicLong nextWakeNanos = new AtomicLong(UNSET); // the drop path wakes a worker at most once per interval
        private volatile boolean shedding;
        private volatile long lastFree; // the last reading, for the warnings
        private volatile String shedCause; // the start warning, built from the reading that started shedding
        private volatile boolean probeFailed; // the failure has been handed out for reporting

        Volume(Probe probe) {
            this.probe = probe;
        }

        /** Producer: true while messages for this volume are being shed. One volatile read. */
        boolean shedding() {
            return shedding;
        }

        long lastFree() {
            return lastFree;
        }

        String shedCause() {
            return shedCause;
        }

        boolean probeFailed() {
            return probeFailed;
        }

        /**
         * Producer, while dropping: true for one caller per interval once a reading is overdue. That
         * caller wakes a worker to take it, so shedding ends even if every worker is asleep, and
         * the other callers don't each unpark the worker.
         */
        boolean shouldWake(long now) {
            long next = nextCheckNanos.get();
            if (next != UNSET && now - next < 0) {
                return false;
            }
            long wake = nextWakeNanos.get();
            return (wake == UNSET || now - wake >= 0) && nextWakeNanos.compareAndSet(wake, now + CHECK_INTERVAL_NANOS);
        }

        /**
         * Any thread: takes a reading if one is due and no other thread is taking it. While the mark
         * isn't set, nothing is read and nothing shed.
         *
         * @return the probe's failure, to be reported by the caller, the first time it fails; else null
         */
        Throwable check(long now) {
            long next = nextCheckNanos.get();
            if ((next != UNSET && now - next < 0) || !nextCheckNanos.compareAndSet(next, now + CHECK_INTERVAL_NANOS)) {
                return null; // not due, or another thread is taking this reading
            }
            long below = shedBelow;
            long above = resumeAbove;
            if (below <= 0) {
                shedding = false;
                return null;
            }
            long free;
            try {
                free = probe.usableBytes();
            } catch (IOException | RuntimeException e) {
                shedding = false; // better to log than to shed on no information
                if (probeFailed) {
                    return null;
                }
                probeFailed = true;
                return e;
            }
            probeFailed = false;
            lastFree = free;
            if (!shedding && free < below) {
                shedCause = "free disk space is " + DiskWatch.human(free) + ", below minFreeDiskSpace=" + DiskWatch.human(below)
                        + "; dropping messages until it is back above " + DiskWatch.human(above);
                shedding = true;
            } else if (shedding && free >= above) {
                shedding = false;
            }
            return null;
        }
    }
}
