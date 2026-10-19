package org.example.efficientlogger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The free-space low-water mark on a fake probe and a fake clock: the shared volume state and one logger's view of it. */
class DiskWatchTest {

    private static final long MS = 1_000_000L;
    private static final long MIB = 1 << 20;
    private static final Path DIR = Paths.get("fake-disk-logs").toAbsolutePath().normalize();

    private final List<String> warnings = new ArrayList<>();
    private final Warnings out = new Warnings((message, cause) -> warnings.add(cause == null ? message : message + " | " + cause.getMessage()));
    private final ProcessLimits limits = new ProcessLimits();
    private long free = 10 * MIB;
    private boolean probeFails;
    private int readings;

    private DiskMark.Volume volume() {
        return limits.disk.volume(DIR, () -> {
            readings++;
            if (probeFails) {
                throw new IOException("no such volume");
            }
            return free;
        });
    }

    /** A logger's view, with the mark set to 1 MiB / 2 MiB. */
    private DiskWatch watch(long now) {
        limits.disk.set(MIB, 2 * MIB);
        return new DiskWatch(volume(), out, now);
    }

    @Test
    void shedsBelowTheFloorAndResumesOnlyAboveTheCeiling() {
        DiskWatch w = watch(0);
        assertEquals(1, readings, "a first reading at once");
        assertFalse(w.shedding());

        free = 512 << 10;
        w.check(999 * MS);
        assertEquals(1, readings, "not due until a second has passed");
        assertFalse(w.shedding());
        w.check(1_000 * MS);
        assertEquals(2, readings);
        assertTrue(w.shedding());

        free = 1536 << 10; // above the floor, below the ceiling
        w.check(2_000 * MS);
        assertTrue(w.shedding(), "hysteresis");
        free = 2 * MIB;
        w.check(3_000 * MS);
        assertFalse(w.shedding());
        free = 1023 << 10;
        w.check(4_000 * MS);
        assertTrue(w.shedding(), "below the floor again");
        assertEquals(5, readings);
        assertTrue(warnings.isEmpty(), "nothing was dropped, so nothing is reported");
    }

    @Test
    void untilSomeBuildSetsTheMarkNothingIsReadOrShed() {
        free = 100 << 10;
        DiskWatch w = new DiskWatch(volume(), out, 0);
        w.check(1_000 * MS);
        assertEquals(0, readings, "the probe is never called");
        assertFalse(w.shedding());

        limits.disk.set(MIB, 2 * MIB); // a later build sets it
        w.check(2_000 * MS);
        assertEquals(1, readings);
        assertTrue(w.shedding(), "and it applies to the logger built before");
    }

    @Test
    void reportsAnEpisodeOfDropsWithTheReadingThatStartedIt() {
        DiskWatch w = watch(0);
        free = 700 << 10;
        w.check(1_000 * MS);
        assertTrue(w.shedding());
        w.recordDrop();
        w.recordDrop();
        w.check(1_500 * MS); // not a reading, but the drops are reported
        assertEquals(List.of("free disk space is 700.0 KiB, below minFreeDiskSpace=1.0 MiB; dropping messages until it is back above 2.0 MiB"),
                warnings);
        free = 3 * MIB;
        w.check(2_000 * MS);
        assertFalse(w.shedding());
        assertEquals("free disk space is back to 3.0 MiB; dropped 2 messages (2 in total)", warnings.get(1));
        assertEquals(2, w.drops());

        free = 100 << 10;
        w.check(3_000 * MS);
        w.recordDrop();
        w.finalReport();
        assertEquals(3, warnings.size(), warnings::toString);
        assertEquals("free disk space is 100.0 KiB, below minFreeDiskSpace=1.0 MiB; dropping messages until it is back above 2.0 MiB;"
                + " dropped 1 message before close (3 in total)", warnings.get(2));
    }

    @Test
    void aProbeThatFailsWarnsOnceAndShedsNothing() {
        free = 100 << 10;
        probeFails = true;
        DiskWatch w = watch(0);
        assertFalse(w.shedding(), "no information: better to log than to shed");
        assertEquals(1, warnings.size(), warnings::toString);
        assertEquals("can't read the free space of the log directory's volume, so minFreeDiskSpace isn't enforced | no such volume",
                warnings.get(0));
        w.check(1_000 * MS);
        assertEquals(1, warnings.size(), "warned once");

        probeFails = false;
        w.check(2_000 * MS);
        assertTrue(w.shedding(), "enforced again once the volume can be read");
        probeFails = true;
        w.check(3_000 * MS);
        assertFalse(w.shedding());
        assertEquals(2, warnings.size(), "and warned again after a recovery");
    }

    @Test
    void anEpisodeCutShortByAProbeFailureSaysSo() {
        DiskWatch w = watch(0);
        free = 100 << 10;
        w.check(1_000 * MS);
        w.recordDrop();
        w.check(1_100 * MS);
        assertEquals(1, warnings.size(), warnings::toString);

        probeFails = true;
        w.check(2_000 * MS);
        assertFalse(w.shedding());
        assertEquals(3, warnings.size(), warnings::toString);
        assertEquals("can't read the free space of the log directory's volume, so minFreeDiskSpace isn't enforced | no such volume",
                warnings.get(1));
        assertEquals("free disk space can no longer be read, so minFreeDiskSpace isn't enforced; dropped 1 message (1 in total)",
                warnings.get(2), "not 'back to' the reading that started the shedding (review 20, C3)");
    }

    @Test
    void oneDroppingCallerPerIntervalWakesAWorkerOnceAReadingIsOverdue() {
        DiskWatch w = watch(0);
        assertFalse(w.shouldWake(999 * MS), "not overdue yet");
        assertTrue(w.shouldWake(1_000 * MS), "overdue: this caller wakes a worker");
        assertFalse(w.shouldWake(1_000 * MS), "the next caller doesn't (review 20, C2)");
        assertFalse(w.shouldWake(1_999 * MS));
        assertTrue(w.shouldWake(2_000 * MS), "still nobody took the reading: one more wake");
        w.check(2_000 * MS); // the worker takes it
        assertFalse(w.shouldWake(2_500 * MS), "nothing is overdue");
        assertTrue(w.shouldWake(3_000 * MS), "overdue again");
    }

    @Test
    void loggersOnOneVolumeShareItsReadingAndItsShedding() {
        DiskWatch a = watch(0);
        DiskWatch b = new DiskWatch(volume(), out, 0); // another logger, same directory
        assertEquals(1, readings, "one reading for both");
        free = 100 << 10;
        a.check(1_000 * MS);
        assertEquals(2, readings);
        assertTrue(b.shedding(), "b sheds on the reading a took");
        b.recordDrop();
        b.check(1_100 * MS);
        assertEquals(1, warnings.size(), "b reports its own drop");
        assertEquals(0, a.drops());
        assertEquals(1, b.drops());
    }

    @Test
    void realDirectoriesOnOneFileStoreShareOneVolume(@TempDir Path dir) throws IOException {
        Path sub = Files.createDirectories(dir.resolve("sub"));
        assertSame(limits.disk.volume(dir, null), limits.disk.volume(sub, null));
    }

    /** A directory with no reachable file store at build gets a volume that keeps looking for one (review 21, S2). */
    @Test
    void aDirectoryWithoutAFileStoreYetGetsAVolumeThatKeepsLooking(@TempDir Path dir) throws IOException {
        Path missing = dir.resolve("not-there-yet");
        limits.disk.set(1, 1);
        DiskMark.Volume v = limits.disk.volume(missing, null);
        DiskWatch w = new DiskWatch(v, out, 0);
        assertEquals(1, warnings.size(), warnings::toString);
        assertTrue(warnings.get(0).startsWith("can't read the free space of the log directory's volume"), warnings.get(0));
        assertFalse(w.shedding());

        Files.createDirectories(missing); // the directory appears (or the volume is mounted)
        w.check(1_000 * MS);
        assertTrue(v.lastFree() > 0, "read from the file store found on the retry");
        assertFalse(v.probeFailed());
        assertEquals(1, warnings.size(), "nothing more to report");
    }

    @Test
    void settingTheMarkMakesEveryVolumeReadAtOnce() {
        DiskWatch w = watch(0); // reads at 0; the next reading would be due at 1 s
        free = 100 << 10;
        w.check(500 * MS);
        assertFalse(w.shedding(), "not due");
        limits.disk.set(MIB, 2 * MIB); // a build sets the mark (again)
        w.check(600 * MS);
        assertTrue(w.shedding(), "a fresh reading at the next check, not an interval later (review 21, S3/S4)");
    }

    @Test
    void humanReadableSizes() {
        assertEquals("0 bytes", DiskWatch.human(0));
        assertEquals("1023 bytes", DiskWatch.human(1023));
        assertEquals("1.0 KiB", DiskWatch.human(1024));
        assertEquals("1.5 MiB", DiskWatch.human(3 * MIB / 2));
        assertEquals("2.0 GiB", DiskWatch.human(2L << 30));
        assertEquals("3.0 TiB", DiskWatch.human(3L << 40));
        assertEquals("4096.0 TiB", DiskWatch.human(4L << 50));
    }
}
