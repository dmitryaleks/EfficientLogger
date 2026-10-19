package org.example.efficientlogger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The shared drop reporter on a fake clock: a start, progress every interval, and an end with the total. */
class DropEpisodeTest {

    private static final long MS = 1_000_000L;

    private final List<String> warnings = new ArrayList<>();
    private final DropEpisode e = new DropEpisode(new Warnings((message, cause) -> warnings.add(message)),
            () -> "cause", () -> "recovered");

    private void drop(int n) {
        for (int i = 0; i < n; i++) {
            e.recordDrop();
        }
    }

    @Test
    void reportsTheStartProgressEveryIntervalAndTheEndWithTheTotal() {
        e.check(0, true);
        assertTrue(warnings.isEmpty(), "no drops: nothing to say");
        drop(2);
        e.check(0, true);
        assertEquals(List.of("cause"), warnings);
        drop(5);
        e.check(5_000 * MS, true);
        assertEquals(1, warnings.size(), "an interval hasn't passed");
        e.check(10_000 * MS, true);
        assertEquals("cause; still dropping: 5 messages since the last report (7 in total)", warnings.get(1), "review 20, C5");
        drop(1);
        e.check(15_000 * MS, true);
        assertEquals(2, warnings.size());
        e.check(20_000 * MS, true);
        assertEquals("cause; still dropping: 1 message since the last report (8 in total)", warnings.get(2));
        e.check(21_000 * MS, false);
        assertEquals("recovered; dropped 8 messages (8 in total)", warnings.get(3), "the end counts the whole episode");
        assertEquals(4, warnings.size());
        assertEquals(8, e.drops());
    }

    @Test
    void aSilentEpisodeIsAnnouncedLateAndItsProgressCountedFromThatReport() {
        drop(1);
        e.check(0, true);
        e.check(0, false);
        assertEquals(List.of("cause", "recovered; dropped 1 message (1 in total)"), warnings);

        drop(2);
        e.check(1_000 * MS, true);
        assertEquals(2, warnings.size(), "a second episode within the interval starts silently");
        drop(1);
        e.check(10_000 * MS, true);
        assertEquals("cause", warnings.get(2), "announced late");
        drop(1);
        e.check(20_000 * MS, true);
        assertEquals("cause; still dropping: 1 message since the last report (5 in total)", warnings.get(3));
        e.finalReport();
        assertEquals("dropped 4 messages before close (5 in total)", warnings.get(4));
        assertEquals(5, warnings.size());
    }
}
