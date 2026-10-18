package org.example.efficientlogger;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.locks.LockSupport;

/**
 * The bounded queue between {@code log} callers (many producers) and one worker (the consumer).
 *
 * <p>Each slot holds references to the caller's id and json strings, so offering allocates
 * nothing. There is no "full" case: a producer offers only after {@link Admission} admitted its
 * message. The capacity is at least the logger-wide message limit, so an admitted message always
 * finds its slot free. A null message slot means "not yet published".
 *
 * <p>Ordering. A producer claims a position with an atomic add on the tail, stores the id
 * plainly, then publishes the message with a release store. The consumer reads the message with
 * an acquire load, then the id. The consumer nulls both slots before the admission release that
 * frees them, and the next producer of that slot is admitted after that release, so its stores
 * never race the nulling.
 *
 * <p>Waking the consumer. It records how it sleeps in PARKED (see {@link #AWAKE}). After
 * publishing, a producer reads PARKED. If the consumer sleeps and this message warrants waking
 * it, the producer CASes PARKED to AWAKE, and only the winner unparks. The consumer writes PARKED
 * and then re-reads TAIL and the admission word, all with volatile access, so either the producer
 * sees the sleep state or the consumer sees the message (Dekker). That's why the re-check is on
 * TAIL, not on a slot: a release store followed by a load isn't ordered.
 */
final class MpscRing {

    /** The consumer is running. */
    static final long AWAKE = 0;
    /** Sleeping with a timeout. Wake it only if the queue has grown past the wake threshold. */
    static final long TIMED = 1;
    /** Sleeping until woken. Wake it for any message. */
    static final long DEEP = 2;

    /** Receives drained messages. */
    interface Handler {
        void accept(String id, String json);
    }

    private static final VarHandle SLOTS =
            MethodHandles.arrayElementVarHandle(Object[].class).withInvokeExactBehavior();
    private static final VarHandle LONGS =
            MethodHandles.arrayElementVarHandle(long[].class).withInvokeExactBehavior();

    // Hot values, each at least 128 bytes from the others and from the array header.
    private static final int TAIL = 16; // next position to claim; producers
    private static final int PARKED = 48; // the consumer's sleep state
    private static final int HEAD = 80; // next position to consume; consumer only, plain access
    private static final int CELLS = HEAD + 17;

    private final Object[] slots; // id at 2i, message at 2i + 1
    private final int mask;
    private final long[] cells = new long[CELLS];
    private final long wakeMessages;
    private final long wakeChars;
    private volatile Thread consumer; // null until attached: unpark(null) does nothing

    /**
     * @param capacity a power of two, at least the logger's maxQueuedMessages
     * @param startIndex the first position; tests start near 2^31 to exercise the int cast
     * @param wakeMessages a TIMED consumer is woken when this many messages are queued...
     * @param wakeChars ...or this many chars
     */
    MpscRing(int capacity, long startIndex, long wakeMessages, long wakeChars) {
        assert Integer.bitCount(capacity) == 1;
        this.slots = new Object[capacity * 2];
        this.mask = capacity - 1;
        this.wakeMessages = wakeMessages;
        this.wakeChars = wakeChars;
        cells[TAIL] = startIndex;
        cells[HEAD] = startIndex;
    }

    /** Sets the thread to unpark. Call before producers can offer. */
    void attachConsumer(Thread thread) {
        consumer = thread;
    }

    /**
     * Publishes a message. Call only after {@link Admission#tryAcquire} admitted it, and pass the
     * admission word it returned; that decides whether a TIMED consumer is worth waking. Nothing
     * here can throw, so a claimed slot is always published.
     */
    void offer(String id, String json, long admitted) {
        long pos = (long) LONGS.getAndAdd(cells, TAIL, 1L);
        int i = ((int) pos & mask) << 1;
        assert slots[i + 1] == null : "ring slot still in use";
        slots[i] = id;
        SLOTS.setRelease(slots, i + 1, (Object) json);
        long parked = (long) LONGS.getVolatile(cells, PARKED);
        if (parked != AWAKE
                && (parked == DEEP || aboveWakeThreshold(admitted))
                && LONGS.compareAndSet(cells, PARKED, parked, AWAKE)) {
            LockSupport.unpark(consumer);
        }
    }

    /**
     * Consumer: passes up to {@code max} published messages to {@code handler}, in order, nulling
     * their slots first. Stops early at an unpublished slot: the ring is empty, or a producer is
     * between claiming and publishing (then {@code head() != tail()}). The caller releases the
     * messages' admission afterwards.
     *
     * @return the number of messages passed on
     */
    int drain(Handler handler, int max) {
        long head = cells[HEAD];
        int n = 0;
        try {
            while (n < max) {
                int i = ((int) head & mask) << 1;
                Object json = SLOTS.getAcquire(slots, i + 1);
                if (json == null) {
                    break;
                }
                Object id = slots[i];
                slots[i] = null;
                slots[i + 1] = null;
                head++;
                n++;
                handler.accept((String) id, (String) json);
            }
        } finally {
            cells[HEAD] = head;
        }
        return n;
    }

    /** True if the queue holds enough to wake a TIMED consumer early. */
    boolean aboveWakeThreshold(long admissionWord) {
        return Admission.messages(admissionWord) >= wakeMessages || Admission.chars(admissionWord) >= wakeChars;
    }

    /** Positions claimed so far. Read by the consumer's Dekker re-check and for stats. */
    long tail() {
        return (long) LONGS.getVolatile(cells, TAIL);
    }

    /** Consumer only. */
    long head() {
        return cells[HEAD];
    }

    long parkedState() {
        return (long) LONGS.getVolatile(cells, PARKED);
    }

    /** Consumer only: the volatile write that pairs with the producers' read. */
    void setParkedState(long state) {
        LONGS.setVolatile(cells, PARKED, state);
    }
}
