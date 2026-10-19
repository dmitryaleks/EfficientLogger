package org.example.efficientlogger;

import java.util.Locale;

/**
 * One logger's view of the process-wide free-space mark ({@link DiskMark}): the volume its
 * directory is on, and its own count and reporting of the messages it shed. The thresholds and the
 * reading are shared; what each logger drops is its own, reported through its own handler via a
 * {@link DropEpisode}.
 */
final class DiskWatch {

    private final DiskMark.Volume volume;
    private final Warnings warnings;
    private final DropEpisode episode;

    /** Takes a first reading at once if one is due, so a logger built on a nearly full volume sheds from its first message. */
    DiskWatch(DiskMark.Volume volume, Warnings warnings, long now) {
        this.volume = volume;
        this.warnings = warnings;
        this.episode = new DropEpisode(warnings, volume::shedCause, () -> volume.probeFailed()
                ? "free disk space can no longer be read, so minFreeDiskSpace isn't enforced"
                : "free disk space is back to " + human(volume.lastFree()));
        check(now);
    }

    /** Producer: true while messages are being shed. One volatile read. */
    boolean shedding() {
        return volume.shedding();
    }

    /** Producer: counts one shed message. */
    void recordDrop() {
        episode.recordDrop();
    }

    /** Messages this logger shed so far. */
    long drops() {
        return episode.drops();
    }

    /** Producer, while dropping: true for one caller per interval once a reading is overdue; that caller wakes a worker. */
    boolean shouldWake(long now) {
        return volume.shouldWake(now);
    }

    /** Any thread: takes a reading if one is due, reports a failure to read once, and reports episode transitions. */
    void check(long now) {
        Throwable failed = volume.check(now);
        if (failed != null) {
            warnings.emit("can't read the free space of the log directory's volume, so minFreeDiskSpace isn't enforced", failed);
        }
        episode.check(now, volume.shedding());
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
