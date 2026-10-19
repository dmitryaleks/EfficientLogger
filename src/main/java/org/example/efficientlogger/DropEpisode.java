package org.example.efficientlogger;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Turns a run of dropped messages, all for one cause, into warnings: one when the run starts and
 * one with the count when it ends, so a cause that keeps dropping can't flood the handler. At most
 * one start is reported per {@link #REPORT_INTERVAL_NANOS}; drops during an episode that went
 * unreported are folded into the next report, so no drop goes uncounted.
 *
 * <p>Producers only increment a counter, so dropping a message never prints or locks. A worker
 * calls {@link #check} now and then with whether the cause still holds; its fast path is two
 * volatile reads. Only {@code check} (under this monitor) changes the episode state, so each
 * report is made once even with several workers. The queue's own episodes
 * ({@link OverflowTracker}), low disk space ({@link DiskWatch}) and the output rate cap
 * ({@link RateLimit}) all report through one of these.
 */
final class DropEpisode {

    static final long REPORT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);

    private static final VarHandle LONGS =
            MethodHandles.arrayElementVarHandle(long[].class).withInvokeExactBehavior();
    private static final int DROPS = 16; // padded: producers hit this only while dropping

    private final long[] cell = new long[DROPS * 2 + 1];
    private final Warnings warnings;
    private final Supplier<String> cause;     // the start warning: what is wrong and what happens meanwhile
    private final Supplier<String> recovered; // the end warning's opening, before "; dropped N"

    private volatile boolean active; // an episode started and hasn't been reported as over
    private volatile long reported; // drops already included in a report
    // Guarded by this:
    private boolean startEmitted; // this episode's start was emitted, not rate-limited away
    private boolean hasReported;  // any warning has been emitted
    private long nextReportNanos; // earliest time to emit the next one

    /**
     * @param cause the start warning, built only when one is emitted
     * @param recovered how the end warning opens, built only when one is emitted
     */
    DropEpisode(Warnings warnings, Supplier<String> cause, Supplier<String> recovered) {
        this.warnings = warnings;
        this.cause = cause;
        this.recovered = recovered;
    }

    /** Producer: counts one dropped message. */
    void recordDrop() {
        long unused = (long) LONGS.getAndAdd(cell, DROPS, 1L);
    }

    /** Messages dropped so far. */
    long drops() {
        return (long) LONGS.getVolatile(cell, DROPS);
    }

    /**
     * Worker: reports the start or end of an episode, rate-limited. At most one transition per call.
     *
     * @param over true while the cause of the drops still holds; once it doesn't, the episode ends
     */
    void check(long now, boolean over) {
        if (!active && drops() == reported) {
            return;
        }
        String message = null;
        synchronized (this) {
            long d = drops();
            if (!active) {
                if (d != reported) { // new drops: an episode begins
                    active = true;
                    startEmitted = false;
                    if (reportDue(now)) {
                        message = cause.get();
                        startEmitted = true;
                    }
                }
            } else if (!over) { // the episode is over
                if (startEmitted || reportDue(now)) {
                    message = recovered.get() + "; dropped " + messages(d - reported) + " (" + d + " in total)";
                    reported = d;
                    active = false;
                    startEmitted = false;
                }
                // else: stay active and unreported until a report is due, so the count isn't lost
            } else if (!startEmitted && reportDue(now)) { // still dropping: announce the episode late
                message = cause.get();
                startEmitted = true;
            }
        }
        if (message != null) {
            warnings.emit(message, null);
        }
    }

    /** Worker, at the end: reports drops not yet reported. Harmless to call more than once. */
    void finalReport() {
        String message = null;
        synchronized (this) {
            long total = drops();
            if (total != reported) {
                message = (active && startEmitted ? "" : cause.get() + "; ")
                        + "dropped " + messages(total - reported) + " before close (" + total + " in total)";
            }
            reported = total;
            active = false;
        }
        if (message != null) {
            warnings.emit(message, null);
        }
    }

    /** True if a warning may be emitted now; records the time when it returns true. */
    private boolean reportDue(long now) {
        if (hasReported && now - nextReportNanos < 0) {
            return false;
        }
        hasReported = true;
        nextReportNanos = now + REPORT_INTERVAL_NANOS;
        return true;
    }

    static String messages(long n) {
        return n == 1 ? "1 message" : n + " messages";
    }
}
