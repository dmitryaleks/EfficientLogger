package org.example.efficientlogger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MpscRingTest {

    private static final long NEVER = Long.MAX_VALUE; // a wake threshold that is never reached

    private record Message(String id, String json) {}

    private static final class Collector implements MpscRing.Handler {
        final List<Message> got = new ArrayList<>();

        @Override
        public void accept(String id, String json) {
            got.add(new Message(id, json));
        }
    }

    private static long word(long messages, long chars) {
        return messages * Admission.ONE_MESSAGE + chars;
    }

    @Test
    void assertionsAreEnabled() {
        // The stress test relies on offer() asserting that each claimed slot is free.
        assertTrue(MpscRing.class.desiredAssertionStatus());
    }

    @Test
    void drainsInOfferOrderAndClearsSlots() {
        MpscRing r = new MpscRing(8, 0, NEVER, NEVER);
        for (int i = 0; i < 5; i++) {
            r.offer("id" + i, "{\"n\":" + i + "}", word(i + 1, 0));
        }
        assertEquals(5, r.tail());
        assertEquals(0, r.head());

        Collector c = new Collector();
        assertEquals(5, r.drain(c, 100));
        for (int i = 0; i < 5; i++) {
            assertEquals(new Message("id" + i, "{\"n\":" + i + "}"), c.got.get(i));
        }
        assertEquals(5, r.head());
        assertEquals(0, r.drain(c, 100), "empty");
    }

    @Test
    void drainStopsAtMax() {
        MpscRing r = new MpscRing(8, 0, NEVER, NEVER);
        for (int i = 0; i < 5; i++) {
            r.offer("a", String.valueOf(i), word(1, 1));
        }
        Collector c = new Collector();
        assertEquals(2, r.drain(c, 2));
        assertEquals(3, r.drain(c, 10));
        assertEquals(List.of("0", "1", "2", "3", "4"), c.got.stream().map(Message::json).toList());
    }

    @Test
    void wrapsAround() {
        MpscRing r = new MpscRing(4, 0, NEVER, NEVER);
        Collector c = new Collector();
        int next = 0;
        for (int round = 0; round < 1000; round++) {
            for (int i = 0; i < 3; i++) {
                r.offer("a", String.valueOf(next++), word(1, 1));
            }
            assertEquals(3, r.drain(c, 10));
        }
        for (int i = 0; i < next; i++) {
            assertEquals(String.valueOf(i), c.got.get(i).json());
        }
    }

    @Test
    void positionsPastIntMaxValueWork() {
        long start = (1L << 31) - 3;
        MpscRing r = new MpscRing(8, start, NEVER, NEVER);
        Collector c = new Collector();
        for (int round = 0; round < 100; round++) {
            for (int i = 0; i < 5; i++) {
                r.offer("a", round + "." + i, word(1, 1));
            }
            assertEquals(5, r.drain(c, 10));
        }
        assertEquals(start + 500, r.head());
        assertEquals(r.head(), r.tail());
        for (int round = 0; round < 100; round++) {
            for (int i = 0; i < 5; i++) {
                assertEquals(round + "." + i, c.got.get(round * 5 + i).json());
            }
        }
    }

    // ---- waking the consumer (producer side) ----

    @Test
    void anAwakeConsumerIsLeftAlone() {
        MpscRing r = new MpscRing(8, 0, 1, 1);
        r.attachConsumer(Thread.currentThread());
        r.offer("a", "{}", word(5, 5));
        assertEquals(MpscRing.AWAKE, r.parkedState());
    }

    @Test
    void aDeepSleeperIsWokenByAnyMessage() {
        LockSupport.parkNanos(1); // clear any permit an earlier test left on this thread
        MpscRing r = new MpscRing(8, 0, NEVER, NEVER);
        r.attachConsumer(Thread.currentThread());
        r.setParkedState(MpscRing.DEEP);

        r.offer("a", "{}", word(1, 2));

        assertEquals(MpscRing.AWAKE, r.parkedState());
        long start = System.nanoTime();
        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(10));
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5), "the unpark should have left a permit");
    }

    @Test
    void aTimedSleeperIsWokenOnlyAboveTheThreshold() {
        MpscRing r = new MpscRing(64, 0, 16, 1000);
        r.attachConsumer(Thread.currentThread());
        r.setParkedState(MpscRing.TIMED);

        r.offer("a", "{}", word(15, 999));
        assertEquals(MpscRing.TIMED, r.parkedState(), "below both thresholds");

        r.offer("a", "{}", word(16, 999));
        assertEquals(MpscRing.AWAKE, r.parkedState(), "message threshold");

        r.setParkedState(MpscRing.TIMED);
        r.offer("a", "{}", word(1, 1000));
        assertEquals(MpscRing.AWAKE, r.parkedState(), "char threshold");
        LockSupport.parkNanos(1); // use up the permit so it can't affect another test
    }

    // ---- many producers ----

    @ParameterizedTest(name = "maxMessages={0}, capacity={1}, maxChars={2}")
    @CsvSource({"5, 8, 1073741824", "8, 8, 1073741824", "16, 16, 1073741824", "16, 16, 20"})
    void manyProducersLoseNothingAndKeepTheirOrder(int maxMessages, int capacity, long maxChars) throws Exception {
        for (int round = 0; round < 3; round++) {
            runProducers(8, 100_000, maxMessages, capacity, maxChars);
        }
    }

    /**
     * Producers send (producer, seq) through admission and a small ring, waiting while it's full.
     * The consumer checks that each producer's messages arrive once, in order, with none missing.
     */
    private static void runProducers(int producers, int perProducer, int maxMessages, int capacity, long maxChars)
            throws Exception {
        Admission admission = new Admission(maxMessages, maxChars);
        MpscRing ring = new MpscRing(capacity, (1L << 31) - 3, NEVER, NEVER);
        String[] ids = new String[producers];
        for (int p = 0; p < producers; p++) {
            ids[p] = String.valueOf((char) ('A' + p));
        }
        String[] seqs = new String[perProducer];
        for (int s = 0; s < perProducer; s++) {
            seqs[s] = String.valueOf(s);
        }

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> producerFailure = new AtomicReference<>();
        Thread[] threads = new Thread[producers];
        boolean[] stop = {false};
        for (int p = 0; p < producers; p++) {
            String id = ids[p];
            threads[p] = new Thread(() -> {
                try {
                    start.await();
                    for (int s = 0; s < perProducer; s++) {
                        String json = seqs[s];
                        long admitted;
                        int spins = 0;
                        while ((admitted = admission.tryAcquire(json.length())) == Admission.REJECTED_FULL) {
                            if (stop[0]) {
                                return;
                            }
                            if (++spins < 100) {
                                Thread.onSpinWait();
                            } else {
                                Thread.yield();
                            }
                        }
                        ring.offer(id, json, admitted);
                    }
                } catch (Throwable t) {
                    producerFailure.compareAndSet(null, t);
                }
            });
            threads[p].setDaemon(true);
            threads[p].start();
        }

        int[] next = new int[producers];
        long[] releasable = {0};
        MpscRing.Handler check = (id, json) -> {
            int p = id.charAt(0) - 'A';
            int seq = Integer.parseInt(json);
            if (seq != next[p]) {
                throw new AssertionError("producer " + p + ": expected " + next[p] + ", got " + seq);
            }
            next[p]++;
            releasable[0] += Admission.delta(json.length());
        };

        long total = (long) producers * perProducer;
        long received = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        start.countDown();
        try {
            while (received < total) {
                int n = ring.drain(check, 64);
                admission.release(releasable[0]);
                releasable[0] = 0;
                received += n;
                if (n == 0) {
                    if (System.nanoTime() > deadline) {
                        throw new AssertionError("timed out after " + received + " of " + total + ": " + Arrays.toString(next));
                    }
                    Thread.yield();
                }
            }
        } finally {
            stop[0] = true; // unblock producers if the consumer failed
            for (Thread t : threads) {
                t.join(10_000);
            }
        }
        if (producerFailure.get() != null) {
            throw new AssertionError("producer failed", producerFailure.get());
        }
        for (int p = 0; p < producers; p++) {
            assertEquals(perProducer, next[p]);
        }
        assertEquals(0, admission.get(), "everything released");
        assertEquals(ring.tail(), ring.head());
        assertEquals(0, ring.drain(check, 64));
    }
}
