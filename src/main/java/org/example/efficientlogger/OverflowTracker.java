package org.example.efficientlogger;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.TimeUnit;

/**
 * Turns dropped messages into warnings, rate-limited so a queue that keeps overflowing can't flood
 * the handler. An episode starts with the first drop ("queue full") and ends when the queue is
 * back to half of both limits ("queue recovered; dropped N"). At most one overflow warning is
 * emitted per {@link #REPORT_INTERVAL_NANOS}; drops in between are folded into the next report's
 * count. Only messages dropped because the queue was full count here; one too large for the queue
 * is reported separately (see {@link Warnings#reportOversizeMessages}).
 *
 * <p>Producers only increment a counter, so dropping a message never prints or locks. The workers
 * call {@link #check} after every batch and before sleeping; its fast path is two volatile reads.
 * Only {@code check} (under this monitor) changes the episode state, so each report is made once
 * even with several workers.
 */
final class OverflowTracker {

    static final long REPORT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);

    private static final VarHandle LONGS =
            MethodHandles.arrayElementVarHandle(long[].class).withInvokeExactBehavior();
    private static final int DROPS = 16; // padded: producers hit this only while the queue is full

    private final long[] cell = new long[DROPS * 2 + 1];
    private final Admission admission;
    private final Warnings warnings;
    private final long lowWaterMessages;
    private final long lowWaterChars;
    private final String limits;

    private volatile boolean active; // an episode started and hasn't been reported as over
    private volatile long reported; // drops already included in a report
    // Guarded by this:
    private boolean startEmitted; // this episode's "queue full" was emitted, not rate-limited away
    private boolean hasReported;  // any overflow warning has been emitted
    private long nextReportNanos; // earliest time to emit the next one

    OverflowTracker(Admission admission, Config config, Warnings warnings) {
        this.admission = admission;
        this.warnings = warnings;
        this.lowWaterMessages = config.lowWaterCount;
        this.lowWaterChars = config.lowWaterChars;
        this.limits = "maxQueuedMessages=" + config.maxQueuedMessages + ", maxQueuedChars=" + config.maxQueuedChars;
    }

    /** Producer: counts one message dropped because the queue was full. */
    void recordDrop() {
        long unused = (long) LONGS.getAndAdd(cell, DROPS, 1L);
    }

    /** Messages dropped so far because the queue was full. */
    long drops() {
        return (long) LONGS.getVolatile(cell, DROPS);
    }

    /** Worker: reports the start or end of an episode, rate-limited. At most one transition per call. */
    void check(long now) {
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
                        message = queueFull();
                        startEmitted = true;
                    }
                }
            } else if (belowLowWater()) { // the episode is over
                if (startEmitted || reportDue(now)) {
                    message = "queue recovered; dropped " + messages(d - reported) + " (" + d + " in total)";
                    reported = d;
                    active = false;
                    startEmitted = false;
                }
                // else: stay active and unreported until a report is due, so the count isn't lost
            } else if (!startEmitted && reportDue(now)) { // still dropping: announce the episode late
                message = queueFull();
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
                message = (active && startEmitted ? "" : "queue full (" + limits + "); ")
                        + "dropped " + messages(total - reported) + " before close (" + total + " in total)";
            }
            reported = total;
            active = false;
        }
        if (message != null) {
            warnings.emit(message, null);
        }
    }

    private boolean belowLowWater() {
        long word = admission.get();
        return Admission.messages(word) <= lowWaterMessages && Admission.chars(word) <= lowWaterChars;
    }

    /** True if an overflow warning may be emitted now; records the time when it returns true. */
    private boolean reportDue(long now) {
        if (hasReported && now - nextReportNanos < 0) {
            return false;
        }
        hasReported = true;
        nextReportNanos = now + REPORT_INTERVAL_NANOS;
        return true;
    }

    private String queueFull() {
        return "queue full (" + limits + "); dropping messages until it is half empty";
    }

    private static String messages(long n) {
        return n == 1 ? "1 message" : n + " messages";
    }
}
