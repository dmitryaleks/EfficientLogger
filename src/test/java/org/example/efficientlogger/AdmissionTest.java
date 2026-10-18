package org.example.efficientlogger;

import static org.example.efficientlogger.Admission.REJECTED_CLOSED;
import static org.example.efficientlogger.Admission.REJECTED_FULL;
import static org.example.efficientlogger.Admission.chars;
import static org.example.efficientlogger.Admission.delta;
import static org.example.efficientlogger.Admission.isClosed;
import static org.example.efficientlogger.Admission.messages;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class AdmissionTest {

    @Test
    void admitsExactlyMaxMessages() {
        Admission a = new Admission(5, 1000);
        for (int i = 1; i <= 5; i++) {
            long word = a.tryAcquire(10);
            assertEquals(i, messages(word));
            assertEquals(10L * i, chars(word));
        }
        assertEquals(REJECTED_FULL, a.tryAcquire(0));

        a.release(delta(10));
        assertTrue(a.tryAcquire(10) > 0);
        assertEquals(REJECTED_FULL, a.tryAcquire(10));
    }

    @Test
    void admitsExactlyMaxChars() {
        Admission a = new Admission(100, 100);
        for (int i = 0; i < 3; i++) {
            assertTrue(a.tryAcquire(30) > 0);
        }
        assertEquals(REJECTED_FULL, a.tryAcquire(30));
        assertEquals(100, chars(a.tryAcquire(10)));
        assertEquals(REJECTED_FULL, a.tryAcquire(1));
        assertEquals(5, messages(a.tryAcquire(0)), "an empty message needs no chars");
    }

    @Test
    void fieldsDontCarryIntoEachOtherAtTheLimits() {
        Admission a = new Admission(Config.QUEUED_MESSAGES_MAX, Config.QUEUED_CHARS_MAX);
        long word = 0;
        for (int i = 0; i < 128; i++) {
            word = a.tryAcquire(Integer.MAX_VALUE);
        }
        assertEquals(128, messages(word));
        assertEquals(128L * Integer.MAX_VALUE, chars(word)); // just under 2^38
        assertFalse(isClosed(word));
        assertEquals(REJECTED_FULL, a.tryAcquire(Integer.MAX_VALUE));

        for (int i = 128; i < Config.QUEUED_MESSAGES_MAX; i++) {
            if (a.tryAcquire(0) <= 0) {
                throw new AssertionError("rejected message " + i);
            }
        }
        word = a.get();
        assertEquals(Config.QUEUED_MESSAGES_MAX, messages(word));
        assertEquals(128L * Integer.MAX_VALUE, chars(word));
        assertFalse(isClosed(word));
        assertEquals(REJECTED_FULL, a.tryAcquire(0));

        a.release(128 * delta(Integer.MAX_VALUE) + (Config.QUEUED_MESSAGES_MAX - 128) * delta(0));
        assertEquals(0, a.get());
    }

    @Test
    void closeStopsAdmissionAndKeepsTheCounts() {
        Admission a = new Admission(10, 100);
        a.tryAcquire(5);
        a.tryAcquire(7);

        long before = a.close();
        assertFalse(isClosed(before));
        assertEquals(2, messages(before));
        assertEquals(REJECTED_CLOSED, a.tryAcquire(1));

        long word = a.get();
        assertTrue(isClosed(word));
        assertEquals(2, messages(word));
        assertEquals(12, chars(word));

        a.release(delta(5) + delta(7));
        assertEquals(Admission.CLOSED, a.get(), "closed and empty");
        assertTrue(isClosed(a.close()), "closing twice is harmless");
    }

    @RepeatedTest(5)
    void neverOvershootsUnderContention() throws Exception {
        int maxMessages = 16;
        long maxChars = 1000;
        Admission a = new Admission(maxMessages, maxChars);
        runConcurrently(8, () -> {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            for (int i = 0; i < 200_000; i++) {
                int len = rnd.nextInt(201);
                long word = a.tryAcquire(len);
                if (word > 0) {
                    if (messages(word) > maxMessages || chars(word) > maxChars) {
                        throw new AssertionError("over the limit: " + messages(word) + " messages, " + chars(word) + " chars");
                    }
                    a.release(delta(len));
                }
            }
            return 0;
        });
        assertEquals(0, a.get(), "everything released");
    }

    @RepeatedTest(5)
    void racingProducersFillTheLimitExactly() throws Exception {
        Admission a = new Admission(10_000, Config.QUEUED_CHARS_MAX);
        List<Integer> admitted = runConcurrently(8, () -> {
            int n = 0;
            while (a.tryAcquire(1) > 0) {
                n++;
            }
            return n;
        });
        assertEquals(10_000, admitted.stream().mapToInt(Integer::intValue).sum());
        assertEquals(10_000, messages(a.get()));
    }

    /** Runs {@code task} on {@code threads} threads at once and returns their results. */
    static <T> List<T> runConcurrently(int threads, java.util.concurrent.Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
