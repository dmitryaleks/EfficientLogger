package org.example.efficientlogger;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Appends JSON strings, one per line, to one file per ID.
 *
 * <pre>{@code
 * try (EfficientLogger logger = EfficientLogger.builder(Path.of("logs"), "events-{id}.json", 100 * 1024).build()) {
 *     logger.log("42", "{\"event\":\"login\"}"); // appends a line to logs/events-42.json, up to 100 MiB
 * }
 * }</pre>
 *
 * <p>By default a background thread does the writing: {@code log} queues the message and returns
 * at once, without allocating. When the queue is full, messages are dropped and a warning is
 * reported. The threads come from a {@link WorkerPool} that all loggers share, so an application
 * with many loggers still has only a few logging threads. In {@linkplain EfficientLoggerBuilder#synchronous() synchronous mode}, {@code log}
 * writes the message itself before returning.
 *
 * <p>Thread-safe. In each file, lines appear in the order their {@code log} calls happened. Files
 * are opened in append mode, so a restarted process continues where the last one stopped.
 */
public interface EfficientLogger extends AutoCloseable {

    /**
     * Starts configuring a logger.
     *
     * @param directory where the files go; created if it doesn't exist
     * @param fileNamePattern file name with exactly one {@code {id}}, e.g. {@code "events-{id}.json"}
     * @param maxFileSizeKb the most a file may grow to, in kilobytes of 1024 bytes, at least 1. A
     *     message that would take its file past this is dropped and counted (in
     *     {@code stats().droppedFileFull()}), with a warning per file; the file is checked again
     *     once a second and written to again once something has truncated it, for example
     *     logrotate with {@code copytruncate}. Applied to each file on its own, but one value for
     *     the whole process: the latest {@code build()} sets it for every logger's files, from
     *     their next record (so do the optional {@code maxFiles}, {@code minFreeDiskSpace} and
     *     {@code maxCharsPerSecond})
     * @throws IllegalArgumentException if {@code maxFileSizeKb} is out of range
     */
    static EfficientLoggerBuilder builder(Path directory, String fileNamePattern, long maxFileSizeKb) {
        return new EfficientLoggerBuilder(directory, fileNamePattern, maxFileSizeKb);
    }

    /**
     * Appends {@code json} and a line separator to the file for {@code id}, creating the file if
     * needed.
     *
     * <p>A message always stays on one line. A raw CR or LF in it is written as a space, and other
     * control characters (except tab), U+2028 and U+2029 as {@code \}{@code uXXXX} escapes. None of
     * this changes what valid JSON means. The JSON is not otherwise checked.
     *
     * @return true if the message was accepted. False if it was dropped: the id is too long for a
     *     file name; the queue was full or the message too large (async); free disk space is below
     *     {@code minFreeDiskSpace} or the logger is over {@code maxCharsPerSecond}, if set; the id
     *     can't be used in a file name for another reason, the file is at {@code maxFileSizeKb}, or
     *     the file has an I/O error (sync; in async mode these are found later and only counted);
     *     or the logger is closed
     * @throws NullPointerException if {@code id} or {@code json} is null
     */
    boolean log(String id, String json);

    /**
     * Waits until every message accepted before this call has been handed to the OS. This is not
     * an fsync. In synchronous mode it returns true at once, because {@code log} already wrote.
     *
     * @return false if the timeout passed first, the calling thread was interrupted (the interrupt
     *     flag stays set), the caller is the logger's own thread, e.g. in a warning handler, which
     *     can't wait for itself, or {@code close} gave up on messages that were never written (see
     *     {@link #close(Duration)})
     */
    boolean flush(Duration timeout);

    /** {@link #flush(Duration)} with the configured close timeout. */
    boolean flush();

    /**
     * Messages {@code log} dropped at once for want of room: the queue was full or a single message
     * was longer than maxQueuedChars (async only), free disk space was below minFreeDiskSpace, or
     * the logger was over maxCharsPerSecond. {@link #stats()} counts every kind of loss, including
     * those found after a message was accepted.
     */
    long droppedCount();

    /** A snapshot of the counters. Allocates, so don't call it on a hot path. */
    LoggerStats stats();

    /**
     * Stops accepting messages, writes everything already accepted, and closes the files. Later
     * {@code log} calls return false. Safe to call more than once, and from several threads.
     *
     * <p>If the timeout passes first, a warning is reported and the rest is still written in the
     * background. Called from the logger's own thread, e.g. in a warning handler, it starts closing
     * and returns false at once. In synchronous mode it waits for writes in progress, and the
     * timeout doesn't apply.
     *
     * <p>One thing can stop the queue from draining: a {@code log} call cut off by an error such as
     * {@code StackOverflowError} between being admitted and queueing its message, or stalled there.
     * Once the queue has made no progress for 2 seconds, the workers give up on what is left, warn,
     * and close the files. Those messages are not written; they show in
     * {@link LoggerStats#queuedMessages()} and, for the ones that had reached a queue,
     * {@link LoggerStats#abandoned()}.
     *
     * @return true if everything was written and closed within the timeout. False if not yet, if
     *     the calling thread was interrupted (the interrupt flag stays set), or if the workers gave
     *     up on messages as described above
     */
    boolean close(Duration timeout);

    /** {@link #close(Duration)} with the configured close timeout. */
    @Override
    void close();
}
