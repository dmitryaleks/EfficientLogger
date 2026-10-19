package org.example.efficientlogger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The output rate cap as a meter on a fake clock: the shared meter and one logger's view of it. */
class RateLimitTest {

    private static final long MS = 1_000_000L;

    private final List<String> warnings = new ArrayList<>();
    private final Warnings out = new Warnings((message, cause) -> warnings.add(message));
    private final RateMeter meter = new RateMeter();

    private RateLimit limit(long charsPerSecond) {
        meter.set(charsPerSecond);
        return new RateLimit(meter, out, 0);
    }

    @Test
    void allowsABurstOfASecondsWorthThenTheRate() {
        RateLimit r = limit(100); // 100 chars/s: a 10-char message is 100 ms
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
    void untilSomeBuildSetsTheCapEveryMessageIsAccepted() {
        RateLimit r = new RateLimit(meter, out, 0);
        assertFalse(r.enabled());
        for (int i = 0; i < 1000; i++) {
            assertTrue(r.tryAcquire(1_000_000, 0));
        }
        meter.set(10); // a later build sets it
        assertTrue(r.enabled());
        assertTrue(r.tryAcquire(10, 0), "the bucket starts full");
        assertTrue(r.tryAcquire(10, 0));
        assertFalse(r.tryAcquire(10, 0));
    }

    @Test
    void theRateCanChangeAndTheOutputChargedSoFarStands() {
        RateLimit r = limit(10); // a 10-char message is a second
        assertTrue(r.tryAcquire(10, 0));
        assertTrue(r.tryAcquire(10, 0)); // 2 s ahead... judged at 1 s
        assertFalse(r.tryAcquire(10, 0), "2 s ahead");
        meter.set(1000); // raised: the next charge is cheap, but the 2 s already charged stand
        assertFalse(r.tryAcquire(10, 500 * MS), "still 1.5 s ahead");
        assertTrue(r.tryAcquire(10, 1_000 * MS), "1 s ahead: within the burst; charged 10 ms now");
        assertFalse(r.tryAcquire(10, 1_000 * MS), "1.01 s ahead");
        assertTrue(r.tryAcquire(10, 1_010 * MS));
    }

    @Test
    void aLargeMessageIsAcceptedWhenCalmAndChargedUpToADay() {
        RateLimit r = limit(1); // 1 char/s
        assertTrue(r.tryAcquire(5000, 0), "far over a second's worth, but the bucket is calm");
        assertFalse(r.tryAcquire(1, 0));
        assertFalse(r.tryAcquire(1, TimeUnit.SECONDS.toNanos(4998)), "5000 s ahead: still 2 s to go");
        assertTrue(r.tryAcquire(1, TimeUnit.SECONDS.toNanos(4999)), "1 s ahead: within the burst");

        RateMeter other = new RateMeter();
        other.set(1);
        RateLimit capped = new RateLimit(other, out, 0);
        assertTrue(capped.tryAcquire(Integer.MAX_VALUE, 0), "would be 68 years at this rate; charged a day");
        assertFalse(capped.tryAcquire(1, TimeUnit.HOURS.toNanos(23)));
        assertTrue(capped.tryAcquire(1, TimeUnit.DAYS.toNanos(1) - RateMeter.BURST_NANOS));
    }

    @Test
    void aNegativeClockIsFine() {
        RateLimit r = limit(10); // nanoTime may be negative; the first charge must still see a full bucket
        long now = -TimeUnit.DAYS.toNanos(400);
        assertTrue(r.tryAcquire(10, now));
        assertTrue(r.tryAcquire(10, now));
        assertFalse(r.tryAcquire(10, now));
        assertTrue(r.tryAcquire(10, now + 1_000 * MS));
    }

    /** Several producers against one meter: the total accepted can't exceed the rate times the time plus one burst. */
    @Test
    void staysExactUnderContention() throws InterruptedException {
        long rate = 1_000_000; // chars/s
        int len = 100;
        RateLimit r = limit(rate);
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
        long bound = (long) (rate * ((elapsed + RateMeter.BURST_NANOS) / 1e9)) + len;
        assertTrue(chars <= bound, "accepted " + chars + " chars in " + elapsed / MS + " ms; the cap allows " + bound);
        assertTrue(chars >= rate * (elapsed / 1e9) * 0.5,
                "accepted only " + chars + " chars in " + elapsed / MS + " ms although the offered load was far over the cap");
    }

    @Test
    void reportsEpisodesThatEndAfterAQuietSecond() {
        RateLimit r = limit(10);
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
