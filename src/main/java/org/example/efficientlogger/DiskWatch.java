package org.example.efficientlogger;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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

    /**
     * The usable space of the volume holding {@code directory}, as the OS reports it (quotas
     * included). The file store is looked up once; each reading is then one system call, which
     * the JDK answers with a small object, so a worker taking readings allocates a few dozen bytes
     * a second. That is the one exception to the worker allocating nothing.
     */
    static Probe forDirectory(Path directory) {
        return new Probe() {
            private volatile FileStore store;

            @Override
            public long usableBytes() throws IOException {
                FileStore s = store;
                if (s == null) {
                    s = Files.getFileStore(directory);
                    store = s;
                }
                return s.getUsableSpace();
            }
        };
    }

    private final Probe probe;
    private final long shedBelow;
    private final long resumeAbove;
    private final Warnings warnings;
    private final AtomicLong nextCheckNanos;
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
        this.episode = new DropEpisode(warnings, () -> shedCause, () -> "free disk space is back to " + human(lastFree));
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

    /** True if the next reading is due, so a producer that is dropping can wake a worker to take it. */
    boolean due(long now) {
        return now - nextCheckNanos.get() >= 0;
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
