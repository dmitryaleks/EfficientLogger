package org.example.efficientlogger;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * The logger-wide queue limits, exact under contention, in one atomic {@code long}:
 *
 * <pre>
 *   bit 63      CLOSED
 *   bits 39-62  messages queued (admitted, not yet released by a worker)
 *   bits 0-38   chars queued
 * </pre>
 *
 * Admitting a message adds {@code ONE_MESSAGE | length} with one CAS. The limits (at most 2^23
 * messages and 2^38-1 chars) guarantee that neither field carries into the next, and a release
 * subtracts exactly what was added, so no field borrows either.
 *
 * <p>Because both limits share one CAS, a producer either gets both or nothing: there is no
 * window where it holds chars but no slot. Once {@link #close} sets CLOSED, no message is admitted
 * any more, so the count can only fall. When it reaches 0, every admitted message has been
 * consumed, including those whose producer was still between admission and publishing.
 */
final class Admission {

    static final long CLOSED = Long.MIN_VALUE;
    static final int CHARS_BITS = 39;
    static final long ONE_MESSAGE = 1L << CHARS_BITS;
    static final long CHARS_MASK = ONE_MESSAGE - 1;

    /** {@link #tryAcquire} result: the logger is closed. */
    static final long REJECTED_CLOSED = 0;
    /** {@link #tryAcquire} result: admitting the message would go over a limit. */
    static final long REJECTED_FULL = -1;

    private static final VarHandle LONGS =
            MethodHandles.arrayElementVarHandle(long[].class).withInvokeExactBehavior();
    private static final int VALUE = 16; // 128 bytes of padding on each side

    private final long[] cell = new long[VALUE * 2 + 1];
    private final long maxMessages;
    private final long maxChars;

    Admission(int maxMessages, long maxChars) {
        assert maxMessages >= 1 && maxMessages <= Config.QUEUED_MESSAGES_MAX;
        assert maxChars >= 1 && maxChars <= Config.QUEUED_CHARS_MAX;
        this.maxMessages = maxMessages;
        this.maxChars = maxChars;
    }

    /**
     * Admits one message of {@code len} chars; the caller has already checked {@code len <=
     * maxChars}.
     *
     * @return the admission word after adding this message (always {@code > 0}), or
     *     {@link #REJECTED_CLOSED}, or {@link #REJECTED_FULL}
     */
    long tryAcquire(int len) {
        assert len >= 0 && len <= maxChars;
        long delta = ONE_MESSAGE | len;
        long v = (long) LONGS.getVolatile(cell, VALUE);
        for (;;) {
            if (v < 0) {
                return REJECTED_CLOSED;
            }
            long next = v + delta;
            if ((next >>> CHARS_BITS) > maxMessages || (next & CHARS_MASK) > maxChars) {
                return REJECTED_FULL; // nothing written, so the limits are never overshot
            }
            long witness = (long) LONGS.compareAndExchange(cell, VALUE, v, next);
            if (witness == v) {
                return next;
            }
            v = witness;
        }
    }

    /** Releases consumed messages: the sum of their {@link #delta}s. */
    void release(long deltaSum) {
        long unused = (long) LONGS.getAndAdd(cell, VALUE, -deltaSum);
    }

    /** Stops admitting messages. Returns the word as it was just before. */
    long close() {
        return (long) LONGS.getAndBitwiseOr(cell, VALUE, CLOSED);
    }

    long get() {
        return (long) LONGS.getVolatile(cell, VALUE);
    }

    /** What admitting a message of {@code len} chars adds to the word. */
    static long delta(int len) {
        return ONE_MESSAGE | len;
    }

    static long messages(long word) {
        return (word & ~CLOSED) >>> CHARS_BITS;
    }

    static long chars(long word) {
        return word & CHARS_MASK;
    }

    static boolean isClosed(long word) {
        return word < 0;
    }
}
