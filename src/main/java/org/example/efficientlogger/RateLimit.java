package org.example.efficientlogger;

import java.util.concurrent.TimeUnit;

/**
 * One logger's view of the process-wide output rate cap ({@link RateMeter}): the shared meter it
 * charges, and its own count and reporting of the messages it dropped for the rate, through a
 * {@link DropEpisode} whose episode ends once this logger has dropped nothing for
 * {@link #QUIET_NANOS}.
 */
final class RateLimit {

    /** An episode of drops ends once none has happened for this long. */
    static final long QUIET_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final RateMeter meter;
    private final DropEpisode episode;
    private volatile long lastDropNanos;

    RateLimit(RateMeter meter, Warnings warnings, long now) {
        this.meter = meter;
        this.lastDropNanos = now - QUIET_NANOS;
        this.episode = new DropEpisode(warnings,
                () -> "output rate limit reached (maxCharsPerSecond=" + meter.charsPerSecond() + "); dropping messages until the rate falls",
                () -> "output rate is back under the limit");
    }

    /** Producer: true if a cap is set, so the clock is worth reading. One volatile read. */
    boolean enabled() {
        return meter.enabled();
    }

    /** Producer: true if a message of {@code len} chars may be sent now, in which case it has been charged. */
    boolean tryAcquire(int len, long now) {
        return meter.tryAcquire(len, now);
    }

    /** Producer: counts one message dropped for the rate. */
    void recordDrop(long now) {
        lastDropNanos = now;
        episode.recordDrop();
    }

    /** Messages this logger dropped for the rate so far. */
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
