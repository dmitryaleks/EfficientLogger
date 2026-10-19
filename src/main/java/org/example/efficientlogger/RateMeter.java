package org.example.efficientlogger;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.TimeUnit;

/**
 * The process-wide cap on the loggers' output ({@code maxCharsPerSecond}): a leaky bucket over the
 * chars of accepted messages, as a meter (GCRA), shared by every logger. The state is one
 * {@code long}, the theoretical arrival time (TAT): the moment the output so far would end at the
 * configured rate. A message is accepted if the TAT is no more than {@link #BURST_NANOS} ahead of
 * now, and is then charged its share of time, so a burst of up to a second's worth of the rate goes
 * through, and the average over any longer period stays under the cap. One CAS per message,
 * lock-free and allocation-free; exact under contention.
 *
 * <p>Acceptance is judged before the message is charged, so a message larger than a second's
 * budget is still accepted when the bucket is calm; it then pushes the TAT far ahead and the next
 * messages are dropped until the debt is paid. A message is never charged more than
 * {@link #MAX_COST_NANOS}, so the TAT can't overflow or lock the loggers out for good. The rate
 * can change: the latest {@code build()} that sets it wins, and the next charge uses it. Until some
 * build sets it, every message is accepted. Each logger counts and reports its own drops through
 * its {@link RateLimit}.
 */
final class RateMeter {

    /** How far ahead of now the output may run: a second's worth of the rate. */
    static final long BURST_NANOS = TimeUnit.SECONDS.toNanos(1);
    /** The most one message is charged, however large. */
    static final long MAX_COST_NANOS = TimeUnit.DAYS.toNanos(1);

    private static final VarHandle LONGS =
            MethodHandles.arrayElementVarHandle(long[].class).withInvokeExactBehavior();
    private static final int TAT = 16; // 128 bytes of padding on each side
    private static final long UNSET = Long.MIN_VALUE; // no charge yet: the bucket is full

    private final long[] cell = new long[TAT * 2 + 1];
    private volatile long charsPerSecond; // 0: the cap isn't set
    private volatile double nanosPerChar;

    RateMeter() {
        cell[TAT] = UNSET;
    }

    /** Sets the rate; the output charged so far stands. */
    void set(long charsPerSecond) {
        assert charsPerSecond >= 1;
        this.nanosPerChar = (double) TimeUnit.SECONDS.toNanos(1) / charsPerSecond;
        this.charsPerSecond = charsPerSecond;
    }

    boolean enabled() {
        return charsPerSecond > 0;
    }

    long charsPerSecond() {
        return charsPerSecond;
    }

    /**
     * Producer: true if a message of {@code len} chars may be sent now, in which case it has been
     * charged; false to drop it. Always true while the cap isn't set.
     */
    boolean tryAcquire(int len, long now) {
        double perChar = nanosPerChar;
        if (perChar == 0) {
            return true;
        }
        long cost = (long) Math.min(len * perChar, MAX_COST_NANOS);
        long tat = (long) LONGS.getVolatile(cell, TAT);
        for (;;) {
            long base = tat != UNSET && tat - now > 0 ? tat : now; // the bucket can't be fuller than full
            if (base - now > BURST_NANOS) {
                return false;
            }
            long witness = (long) LONGS.compareAndExchange(cell, TAT, tat, base + cost);
            if (witness == tat) {
                return true;
            }
            tat = witness;
        }
    }
}
