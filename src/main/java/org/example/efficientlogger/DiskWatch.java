package org.example.efficientlogger;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The low-water mark on free disk space ({@code minFreeDiskSpace}): sheds every message while the
 * log directory's volume has less than {@code shedBelow} bytes free, until it has at least
 * {@code resumeAbove} again. The gap between the two is hysteresis, so a volume hovering around the
 * mark doesn't flip the logger on and off.
 *
 * <p>Free space is sampled at most once per {@link #CHECK_INTERVAL_NANOS}, by whichever thread
 * calls {@link #check} first after the interval: a worker after a batch or before sleeping, or in
 * sync mode the caller. The sample is one system call. Between samples, {@code log()} reads one
 * volatile flag. If the volume can't be queried, nothing is shed and that is warned about once.
 * Drops are counted and reported through a {@link DropEpisode}.
 */
final class DiskWatch {

    /** How the free space is read; one system call. Tests supply their own. */
    interface Probe {
        long usableBytes() throws IOException;
    }

    static final long CHECK_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

    /** One reading per volume per interval, shared by every logger on that volume. Never shrinks: one entry per volume. */
    private static final ConcurrentHashMap<FileStore, VolumeReading> VOLUMES = new ConcurrentHashMap<>();

    /**
     * The usable space of the volume holding {@code directory}, as the OS reports it (quotas
     * included). The file store is looked up once, and its readings are shared by every logger on
     * the same volume, so a hundred loggers on one disk take one reading a second between them.
     * Each reading is one system call, which the JDK answers with a small object, so the thread
     * taking it allocates a few dozen bytes a second: the one exception to the worker allocating
     * nothing. Like a write, the call can stall on a hung network volume.
     */
    static Probe forDirectory(Path directory) {
        return new Probe() {
            private volatile VolumeReading volume;

            @Override
            public long usableBytes() throws IOException {
                VolumeReading v = volume;
                if (v == null) {
                    v = VOLUMES.computeIfAbsent(Files.getFileStore(directory), VolumeReading::new);
                    volume = v;
                }
                return v.read();
            }
        };
    }

    /**
     * The last reading of one volume, reused while younger than the interval. A thread that finds
     * another already refreshing it uses the last reading rather than wait, so a stalled volume
     * stalls one thread, not every logger on it; only the very first reading is waited for.
     */
    private static final class VolumeReading {
        private final FileStore store;
        private final ReentrantLock refreshing = new ReentrantLock();
        private volatile long bytes = -1; // -1: no reading yet
        private volatile long atNanos;

        VolumeReading(FileStore store) {
            this.store = store;
        }

        long read() throws IOException {
            long now = System.nanoTime();
            long last = bytes;
            if (last >= 0 && now - atNanos < CHECK_INTERVAL_NANOS) {
                return last;
            }
            if (last >= 0) {
                if (!refreshing.tryLock()) {
                    return last;
                }
            } else {
                refreshing.lock();
            }
            try {
                last = bytes;
                if (last >= 0 && System.nanoTime() - atNanos < CHECK_INTERVAL_NANOS) {
                    return last; // refreshed while we waited
                }
                long fresh = store.getUsableSpace();
                bytes = fresh;
                atNanos = System.nanoTime();
                return fresh;
            } finally {
                refreshing.unlock();
            }
        }
    }

    private final Probe probe;
    private final long shedBelow;
    private final long resumeAbove;
    private final Warnings warnings;
    private final AtomicLong nextCheckNanos;
    private final AtomicLong nextWakeNanos; // the drop path wakes a worker at most once per interval
    private final DropEpisode episode;
    private volatile boolean shedding;
    private volatile long lastFree; // the last reading, for the warnings
    private volatile String shedCause; // the start warning, built from the reading that started shedding
    private volatile boolean probeFailed; // the failure has been warned about

    /**
     * Takes a first reading at once, so a logger built on a nearly full volume sheds from its first
     * message.
     *
     * @param shedBelow shed while free space is below this many bytes
     * @param resumeAbove resume once it is at least this many bytes; at least {@code shedBelow}
     */
    DiskWatch(Probe probe, long shedBelow, long resumeAbove, Warnings warnings, long now) {
        assert shedBelow >= 1 && resumeAbove >= shedBelow;
        this.probe = probe;
        this.shedBelow = shedBelow;
        this.resumeAbove = resumeAbove;
        this.warnings = warnings;
        this.nextCheckNanos = new AtomicLong(now);
        this.nextWakeNanos = new AtomicLong(now);
        this.episode = new DropEpisode(warnings, () -> shedCause, () -> probeFailed
                ? "free disk space can no longer be read, so minFreeDiskSpace isn't enforced"
                : "free disk space is back to " + human(lastFree));
        check(now);
    }

    /** Producer: true while messages are being shed. One volatile read. */
    boolean shedding() {
        return shedding;
    }

    /** Producer: counts one shed message. */
    void recordDrop() {
        episode.recordDrop();
    }

    /** Messages shed so far. */
    long drops() {
        return episode.drops();
    }

    /**
     * Producer, while dropping: true for one caller per interval once a reading is overdue. That
     * caller wakes a worker to take it, so shedding ends even if every worker is asleep, and the
     * other callers don't each unpark the worker (a spurious wake-up for one blocked in a handler).
     */
    boolean shouldWake(long now) {
        if (now - nextCheckNanos.get() < 0) {
            return false;
        }
        long wake = nextWakeNanos.get();
        return now - wake >= 0 && nextWakeNanos.compareAndSet(wake, now + CHECK_INTERVAL_NANOS);
    }

    /**
     * Any thread: takes a reading if one is due (unless another thread is taking it), then reports
     * episode transitions. Two volatile reads when nothing is due and nothing is being dropped.
     */
    void check(long now) {
        long next = nextCheckNanos.get();
        if (now - next >= 0 && nextCheckNanos.compareAndSet(next, now + CHECK_INTERVAL_NANOS)) {
            sample(now);
        }
        episode.check(now, shedding);
    }

    private void sample(long now) {
        long free;
        try {
            free = probe.usableBytes();
        } catch (IOException | RuntimeException e) {
            if (!probeFailed) {
                probeFailed = true;
                warnings.emit("can't read the free space of the log directory's volume, so minFreeDiskSpace isn't enforced", e);
            }
            shedding = false; // better to log than to shed on no information
            return;
        }
        probeFailed = false;
        lastFree = free;
        if (!shedding && free < shedBelow) {
            shedCause = "free disk space is " + human(free) + ", below minFreeDiskSpace=" + human(shedBelow)
                    + "; dropping messages until it is back above " + human(resumeAbove);
            shedding = true;
        } else if (shedding && free >= resumeAbove) {
            shedding = false;
        }
    }

    /** Worker, at the end: reports shed messages not yet reported. */
    void finalReport() {
        episode.finalReport();
    }

    /** A byte count for a warning: bytes, or KiB, MiB or GiB to one decimal. */
    static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " bytes";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double v = bytes / 1024.0;
        int u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format(Locale.ROOT, "%.1f %s", v, units[u]);
    }
}
