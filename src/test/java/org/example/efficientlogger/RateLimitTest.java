package org.example.efficientlogger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The output rate cap as a meter on a fake clock. */
class RateLimitTest {

    private static final long MS = 1_000_000L;

    private final List<String> warnings = new ArrayList<>();
    private final Warnings out = new Warnings((message, cause) -> warnings.add(message));

    @Test
    void allowsABurstOfASecondsWorthThenTheRate() {
        RateLimit r = new RateLimit(100, out, 0); // 100 chars/s: a 10-char message is 100 ms
        for (int i = 0; i < 10; i++) {
            assertTrue(r.tryAcquire(10, 0), "message " + i + " of the burst");
        }
        assertTrue(r.tryAcquire(10, 0), "the 11th: judged before it is charged, with the output exactly a second ahead");
        assertFalse(r.tryAcquire(10, 0), "the 12th: 1.1 s ahead");
        assertFalse(r.tryAcquire(1, 50 * MS), "any size: still 1.05 s ahead");
        assertTrue(r.tryAcquire(10, 100 * MS), "1.0 s ahead again");
        assertFalse(r.tryAcquire(10, 100 * MS));

        // Over a long stretch, calls at twice the rate are accepted at the rate.
        long now = 10_000 * MS;
        int accepted = 0;
        for (int i = 0; i < 200; i++) {
            now += 50 * MS; // a 10-char message every 50 ms is 200 chars/s offered
            if (r.tryAcquire(10, now)) {
                accepted++;
            }
        }
        // 10 s at 100 chars/s is 100 messages, plus up to a second's burst since the bucket was idle.
        assertTrue(accepted >= 100 && accepted <= 110, "accepted " + accepted);
    }

    @Test
    void aLargeMessageIsAcceptedWhenCalmAndChargedUpToADay() {
        RateLimit r = new RateLimit(1, out, 0); // 1 char/s
        assertTrue(r.tryAcquire(5000, 0), "far over a second's worth, but the bucket is calm");
        assertFalse(r.tryAcquire(1, 0));
        assertFalse(r.tryAcquire(1, TimeUnit.SECONDS.toNanos(4998)), "5000 s ahead: still 2 s to go");
        assertTrue(r.tryAcquire(1, TimeUnit.SECONDS.toNanos(4999)), "1 s ahead: within the burst");

        RateLimit capped = new RateLimit(1, out, 0);
        assertTrue(capped.tryAcquire(Integer.MAX_VALUE, 0), "would be 68 years at this rate; charged a day");
        assertFalse(capped.tryAcquire(1, TimeUnit.HOURS.toNanos(23)));
        assertTrue(capped.tryAcquire(1, TimeUnit.DAYS.toNanos(1) - RateLimit.BURST_NANOS));
    }

    /** Several producers against one meter: the total accepted can't exceed the rate times the time plus one burst. */
    @Test
    void staysExactUnderContention() throws InterruptedException {
        long rate = 1_000_000; // chars/s
        int len = 100;
        RateLimit r = new RateLimit(rate, out, System.nanoTime());
        long start = System.nanoTime();
        long until = start + 500 * MS;
        AtomicLong accepted = new AtomicLong();
        Thread[] producers = new Thread[4];
        for (int i = 0; i < producers.length; i++) {
            producers[i] = new Thread(() -> {
                long n = 0;
                while (System.nanoTime() - until < 0) {
                    if (r.tryAcquire(len, System.nanoTime())) {
                        n++;
                    }
                }
                accepted.addAndGet(n);
            });
            producers[i].start();
        }
        for (Thread p : producers) {
            p.join();
        }
        long elapsed = System.nanoTime() - start;
        long chars = accepted.get() * len;
        long bound = (long) (rate * ((elapsed + RateLimit.BURST_NANOS) / 1e9)) + len;
        assertTrue(chars <= bound, "accepted " + chars + " chars in " + elapsed / MS + " ms; the cap allows " + bound);
        assertTrue(chars >= rate * (elapsed / 1e9) * 0.5,
                "accepted only " + chars + " chars in " + elapsed / MS + " ms although the offered load was far over the cap");
    }

    @Test
    void reportsEpisodesThatEndAfterAQuietSecond() {
        RateLimit r = new RateLimit(10, out, 0);
        assertTrue(r.tryAcquire(10, 0));
        assertTrue(r.tryAcquire(10, 0));
        assertFalse(r.tryAcquire(10, 0));
        r.recordDrop(0);
        assertFalse(r.tryAcquire(10, 500 * MS));
        r.recordDrop(500 * MS);
        r.check(600 * MS);
        assertEquals(List.of("output rate limit reached (maxCharsPerSecond=10); dropping messages until the rate falls"), warnings);
        r.check(1_400 * MS);
        assertEquals(1, warnings.size(), "less than a second since the last drop");
        r.check(1_500 * MS);
        assertEquals("output rate is back under the limit; dropped 2 messages (2 in total)", warnings.get(1));
        assertEquals(2, r.drops());

        r.recordDrop(2_000 * MS);
        r.finalReport();
        assertEquals("output rate limit reached (maxCharsPerSecond=10); dropping messages until the rate falls;"
                + " dropped 1 message before close (3 in total)", warnings.get(2));
    }
}
