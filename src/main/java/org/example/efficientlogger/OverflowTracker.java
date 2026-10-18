package org.example.efficientlogger;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Turns dropped messages into one warning per overflow episode. An episode starts with the first
 * drop. It ends when the queue is back down to half of both limits, and then a second line reports
 * how many messages it cost.
 *
 * <p>Producers only increment a counter, so dropping a message never prints or locks. The workers
 * call {@link #check} after every batch; it costs two volatile reads unless something changed.
 * Only {@code check} (under this monitor) changes the episode state, so each episode is reported
 * exactly once even with several workers.
 */
final class OverflowTracker {

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

    OverflowTracker(Admission admission, Config config, Warnings warnings) {
        this.admission = admission;
        this.warnings = warnings;
        this.lowWaterMessages = config.lowWaterCount;
        this.lowWaterChars = config.lowWaterChars;
        this.limits = "maxQueuedMessages=" + config.maxQueuedMessages + ", maxQueuedChars=" + config.maxQueuedChars;
    }

    /** Producer: counts one dropped message. */
    void recordDrop() {
        long unused = (long) LONGS.getAndAdd(cell, DROPS, 1L);
    }

    /** Messages dropped so far. */
    long drops() {
        return (long) LONGS.getVolatile(cell, DROPS);
    }

    /** Worker: reports the start or end of an episode. At most one of the two per call. */
    void check() {
        if (!active && drops() == reported) {
            return;
        }
        String message = null;
        synchronized (this) {
            if (!active) {
                if (drops() != reported) {
                    active = true;
                    message = "queue full (" + limits + "); dropping messages until it is half empty";
                }
            } else {
                // Read the queue before the drops: a drop counted after this read belongs to the next episode.
                long word = admission.get();
                if (Admission.messages(word) <= lowWaterMessages && Admission.chars(word) <= lowWaterChars) {
                    long total = drops();
                    message = "queue recovered; dropped " + messages(total - reported) + " (" + total + " in total)";
                    reported = total;
                    active = false;
                }
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
                message = (active ? "" : "queue full (" + limits + "); ")
                        + "dropped " + messages(total - reported) + " before close (" + total + " in total)";
            }
            reported = total;
            active = false;
        }
        if (message != null) {
            warnings.emit(message, null);
        }
    }

    private static String messages(long n) {
        return n == 1 ? "1 message" : n + " messages";
    }
}
