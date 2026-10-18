package org.example.efficientlogger;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Appends JSON strings, one per line, to one file per ID.
 *
 * <pre>{@code
 * try (EfficientLogger logger = EfficientLogger.builder(Path.of("logs"), "events-{id}.json").build()) {
 *     logger.log("42", "{\"event\":\"login\"}"); // appends a line to logs/events-42.json
 * }
 * }</pre>
 *
 * <p>By default a background thread does the writing: {@code log} queues the message and returns
 * at once, without allocating. When the queue is full, messages are dropped and a warning is
 * reported. In {@linkplain EfficientLoggerBuilder#synchronous() synchronous mode}, {@code log}
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
     */
    static EfficientLoggerBuilder builder(Path directory, String fileNamePattern) {
        return new EfficientLoggerBuilder(directory, fileNamePattern);
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
     *     file name; the queue was full or the message too large (async); the id can't be used in a
     *     file name for another reason, or the file has an I/O error (sync; in async mode these are
     *     found later and only counted); or the logger is closed
     * @throws NullPointerException if {@code id} or {@code json} is null
     */
    boolean log(String id, String json);

    /**
     * Waits until every message accepted before this call has been handed to the OS. This is not
     * an fsync. In synchronous mode it returns true at once, because {@code log} already wrote.
     *
     * @return false if the timeout passed first, the calling thread was interrupted (the interrupt
     *     flag stays set), or the caller is the logger's own thread, e.g. in a warning handler, which
     *     can't wait for itself
     */
    boolean flush(Duration timeout);

    /** {@link #flush(Duration)} with the configured close timeout. */
    boolean flush();

    /**
     * Messages dropped because the queue was full or a single message was longer than
     * maxQueuedChars. Always 0 in synchronous mode. {@link #stats()} counts every kind of loss.
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
     * @return true if everything was written and closed within the timeout. False if not yet, or if
     *     the calling thread was interrupted (the interrupt flag stays set)
     */
    boolean close(Duration timeout);

    /** {@link #close(Duration)} with the configured close timeout. */
    @Override
    void close();
}
