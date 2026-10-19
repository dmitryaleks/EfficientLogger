package org.example.efficientlogger;

/**
 * Turns messages dropped because the queue was full into warnings. An episode starts with the
 * first drop ("queue full") and ends when the queue is back to half of both limits ("queue
 * recovered; dropped N"), reported through a {@link DropEpisode}, which rate-limits the reports
 * to one per {@link DropEpisode#REPORT_INTERVAL_NANOS} and folds the drops in between into the
 * next one. Only messages dropped because the queue was full count here; one too large for the
 * queue is reported separately (see {@link Warnings#reportOversizeMessages}).
 */
final class OverflowTracker {

    static final long REPORT_INTERVAL_NANOS = DropEpisode.REPORT_INTERVAL_NANOS;

    private final Admission admission;
    private final long lowWaterMessages;
    private final long lowWaterChars;
    private final DropEpisode episode;

    OverflowTracker(Admission admission, Config config, Warnings warnings) {
        this.admission = admission;
        this.lowWaterMessages = config.lowWaterCount;
        this.lowWaterChars = config.lowWaterChars;
        String limits = "maxQueuedMessages=" + config.maxQueuedMessages + ", maxQueuedChars=" + config.maxQueuedChars;
        this.episode = new DropEpisode(warnings,
                () -> "queue full (" + limits + "); dropping messages until it is half empty",
                () -> "queue recovered");
    }

    /** Producer: counts one message dropped because the queue was full. */
    void recordDrop() {
        episode.recordDrop();
    }

    /** Messages dropped so far because the queue was full. */
    long drops() {
        return episode.drops();
    }

    /** Worker: reports the start or end of an episode, rate-limited. At most one transition per call. */
    void check(long now) {
        episode.check(now, !belowLowWater());
    }

    /** Worker, at the end: reports drops not yet reported. Harmless to call more than once. */
    void finalReport() {
        episode.finalReport();
    }

    private boolean belowLowWater() {
        long word = admission.get();
        return Admission.messages(word) <= lowWaterMessages && Admission.chars(word) <= lowWaterChars;
    }
}
