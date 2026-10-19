package org.example.efficientlogger;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.TimeUnit;

/**
 * The cap on the logger's own output ({@code maxCharsPerSecond}): a leaky bucket over the chars of
 * accepted messages, as a meter (GCRA). The state is one {@code long}, the theoretical arrival
 * time (TAT): the moment the output so far would end at the configured rate. A message is accepted
 * if the TAT is no more than {@link #BURST_NANOS} ahead of now, and is then charged its share of
 * time, so a burst of up to a second's worth of the rate goes through, and the average over any
 * longer period stays under the cap. One CAS per message, lock-free and allocation-free; exact
 * under contention.
 *
 * <p>Acceptance is judged before the message is charged, so a message larger than a second's
 * budget is still accepted when the bucket is calm; it then pushes the TAT far ahead and the next
 * messages are dropped until the debt is paid. A message is never charged more than
 * {@link #MAX_COST_NANOS}, so the TAT can't overflow or lock the logger out for good. Drops are
 * counted and reported through a {@link DropEpisode}, whose episode ends once no message has been
 * dropped for {@link #QUIET_NANOS}.
 */
final class RateLimit {

    /** How far ahead of now the output may run: a second's worth of the rate. */
    static final long BURST_NANOS = TimeUnit.SECONDS.toNanos(1);
    /** The most one message is charged, however large. */
    static final long MAX_COST_NANOS = TimeUnit.DAYS.toNanos(1);
    /** An episode of drops ends once none has happened for this long. */
    static final long QUIET_NANOS = TimeUnit.SECONDS.toNanos(1);

    private static final VarHandle LONGS =
            MethodHandles.arrayElementVarHandle(long[].class).withInvokeExactBehavior();
    private static final int TAT = 16; // 128 bytes of padding on each side

    private final long[] cell = new long[TAT * 2 + 1];
    private final double nanosPerChar;
    private final DropEpisode episode;
    private volatile long lastDropNanos;

    RateLimit(long charsPerSecond, Warnings warnings, long now) {
        assert charsPerSecond >= 1;
        this.nanosPerChar = (double) TimeUnit.SECONDS.toNanos(1) / charsPerSecond;
        this.cell[TAT] = now;
        this.lastDropNanos = now - QUIET_NANOS;
        this.episode = new DropEpisode(warnings,
                () -> "output rate limit reached (maxCharsPerSecond=" + charsPerSecond + "); dropping messages until the rate falls",
                () -> "output rate is back under the limit");
    }

    /**
     * Producer: true if a message of {@code len} chars may be sent now, in which case it has been
     * charged; false to drop it.
     */
    boolean tryAcquire(int len, long now) {
        long cost = (long) Math.min(len * nanosPerChar, MAX_COST_NANOS);
        long tat = (long) LONGS.getVolatile(cell, TAT);
        for (;;) {
            long base = tat - now > 0 ? tat : now; // the bucket can't be fuller than full
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

    /** Producer: counts one message dropped for the rate. */
    void recordDrop(long now) {
        lastDropNanos = now;
        episode.recordDrop();
    }

    /** Messages dropped so far for the rate. */
    long drops() {
        return episode.drops();
    }

    /** Worker (or, in sync mode, the caller): reports episode transitions. Two volatile reads when idle. */
    void check(long now) {
        episode.check(now, now - lastDropNanos < QUIET_NANOS);
    }

    /** At the end: reports drops not yet reported. */
    void finalReport() {
        episode.finalReport();
    }
}
