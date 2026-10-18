package org.example.efficientlogger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;

/**
 * Configures an {@link EfficientLogger}; get one from {@link EfficientLogger#builder}. Files live in
 * {@code directory} and are named by {@code fileNamePattern}, which contains exactly one
 * {@code {id}}, for example {@code "events-{id}.json"}.
 *
 * <p>Each setter checks its own argument immediately. The pattern, the directory and combinations
 * of options are checked when the logger is built. Options marked "async only" or "sync only" can't
 * be combined with the other mode.
 */
public final class EfficientLoggerBuilder {

    // Read by Config. A null option means "not set": use the default.
    final Path directory;
    final String fileNamePattern;
    boolean synchronous;
    Integer workerThreads;
    Integer maxQueuedMessages;
    Long maxQueuedChars;
    Integer fileBufferBytes;
    Integer maxOpenFiles;
    Long flushIntervalMillis;
    Boolean eagerWakeup;
    Duration closeTimeout;
    Boolean registerShutdownHook;
    ThreadFactory threadFactory;
    WarningHandler warningHandler;
    String lineSeparator;
    Integer syncStripes;

    EfficientLoggerBuilder(Path directory, String fileNamePattern) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.fileNamePattern = Objects.requireNonNull(fileNamePattern, "fileNamePattern");
    }

    /**
     * Number of worker threads, 1 to 16. Default 1. A given ID is always handled by the same worker,
     * so each file's lines stay in order. Async only.
     */
    public EfficientLoggerBuilder workerThreads(int threads) {
        this.workerThreads = checkRange("workerThreads", threads, 1, Config.WORKER_THREADS_MAX);
        return this;
    }

    /**
     * Writes each message on the calling thread before {@code log} returns, with no queue and no extra
     * threads. By default the logger is asynchronous.
     */
    public EfficientLoggerBuilder synchronous() {
        this.synchronous = true;
        return this;
    }

    /**
     * Maximum number of messages waiting to be written, 1 to 2^23 (8,388,608). Default 65,536.
     * Messages beyond the limit are dropped. Async only.
     */
    public EfficientLoggerBuilder maxQueuedMessages(int messages) {
        this.maxQueuedMessages = checkRange("maxQueuedMessages", messages, 1, Config.QUEUED_MESSAGES_MAX);
        return this;
    }

    /**
     * Maximum total length, in chars, of the messages waiting to be written, 1 to 2^38-1. Default
     * 16 Mi (16,777,216). Messages beyond the limit are dropped, and a single message longer than
     * the limit is always dropped. Async only.
     */
    public EfficientLoggerBuilder maxQueuedChars(long chars) {
        this.maxQueuedChars = checkRange("maxQueuedChars", chars, 1, Config.QUEUED_CHARS_MAX);
        return this;
    }

    /**
     * Size of a write buffer, 64 bytes to 16 MiB. Default 8192. Async mode holds one per file with
     * unwritten data; sync mode one per stripe. Larger messages are written in pieces.
     */
    public EfficientLoggerBuilder fileBufferBytes(int bytes) {
        this.fileBufferBytes = checkRange(
                "fileBufferBytes", bytes, Config.FILE_BUFFER_BYTES_MIN, Config.FILE_BUFFER_BYTES_MAX);
        return this;
    }

    /**
     * Soft cap on the number of files open at once, at least 1. Default 1024. When it is reached,
     * the least recently used file is closed; it is reopened in append mode when next needed.
     */
    public EfficientLoggerBuilder maxOpenFiles(int files) {
        this.maxOpenFiles = checkRange("maxOpenFiles", files, 1, Integer.MAX_VALUE);
        return this;
    }

    /**
     * Longest time an accepted message waits before it is handed to the OS, 0 to 60,000 ms.
     * Default 100. 0 writes after every batch and implies {@link #eagerWakeup}. Async only.
     */
    public EfficientLoggerBuilder flushIntervalMillis(long millis) {
        this.flushIntervalMillis = checkRange("flushIntervalMillis", millis, 0, Config.FLUSH_INTERVAL_MILLIS_MAX);
        return this;
    }

    /**
     * Wakes the worker for every message, instead of only when the queue is a quarter full or the
     * flush interval has passed. This gives lower latency at a higher cost per message. Default
     * false. Async only.
     */
    public EfficientLoggerBuilder eagerWakeup(boolean eager) {
        this.eagerWakeup = eager;
        return this;
    }

    /** How long {@code close()} waits for queued messages to be written. Default 10 seconds. */
    public EfficientLoggerBuilder closeTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "closeTimeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("closeTimeout must not be negative, was " + timeout);
        }
        this.closeTimeout = timeout;
        return this;
    }

    /** Closes the logger at JVM exit so queued messages are written. Default true. Async only. */
    public EfficientLoggerBuilder registerShutdownHook(boolean register) {
        this.registerShutdownHook = register;
        return this;
    }

    /**
     * Creates the worker threads. By default they are daemon threads named
     * {@code efficient-logger-<n>-worker-<i>}. Async only.
     */
    public EfficientLoggerBuilder threadFactory(ThreadFactory factory) {
        this.threadFactory = Objects.requireNonNull(factory, "threadFactory");
        return this;
    }

    /** Receives the logger's own warnings. Default {@link WarningHandler#stderr()}. */
    public EfficientLoggerBuilder warningHandler(WarningHandler handler) {
        this.warningHandler = Objects.requireNonNull(handler, "warningHandler");
        return this;
    }

    /** Written after each message: {@code "\n"} (the default) or {@code "\r\n"}. */
    public EfficientLoggerBuilder lineSeparator(String separator) {
        Objects.requireNonNull(separator, "lineSeparator");
        if (!separator.equals("\n") && !separator.equals("\r\n")) {
            throw new IllegalArgumentException("lineSeparator must be \"\\n\" or \"\\r\\n\"");
        }
        this.lineSeparator = separator;
        return this;
    }

    /**
     * Number of independent locks in sync mode, a power of two from 1 to 256. Default 16. A given ID
     * always uses the same lock; different locks don't block each other. Sync only.
     */
    public EfficientLoggerBuilder syncStripes(int stripes) {
        checkRange("syncStripes", stripes, 1, Config.SYNC_STRIPES_MAX);
        if (Integer.bitCount(stripes) != 1) {
            throw new IllegalArgumentException("syncStripes must be a power of two, was " + stripes);
        }
        this.syncStripes = stripes;
        return this;
    }

    /**
     * Creates the logger, and its directory if that doesn't exist.
     *
     * @throws IllegalArgumentException if the pattern is invalid or options conflict
     * @throws UncheckedIOException if the directory can't be created
     */
    public EfficientLogger build() {
        if (!synchronous) {
            // Built and unit-tested (M6); switched on once the multi-threaded tests pass (M7).
            throw new UnsupportedOperationException("asynchronous mode isn't available yet; use synchronous()");
        }
        return build(new TestHooks());
    }

    EfficientLogger build(TestHooks hooks) {
        Config config = buildConfig();
        try {
            hooks.sinks.createDirectoryIfMissing(config.directory);
        } catch (IOException e) {
            throw new UncheckedIOException("can't create directory " + config.directory, e);
        }
        return config.synchronous ? new SyncLogger(config, hooks) : new AsyncLogger(config, hooks);
    }

    /** Validates the options and resolves defaults. */
    Config buildConfig() {
        return new Config(this);
    }

    private static int checkRange(String option, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(option + " must be between " + min + " and " + max + ", was " + value);
        }
        return value;
    }

    private static long checkRange(String option, long value, long min, long max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(option + " must be between " + min + " and " + max + ", was " + value);
        }
        return value;
    }
}
