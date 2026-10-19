package org.example.efficientlogger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

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
    final long maxFileSizeKb;
    boolean synchronous;
    Long minFreeDiskBytes;
    Long resumeFreeDiskBytes;
    Long maxCharsPerSecond;
    Integer workerThreads;
    Integer maxQueuedMessages;
    Long maxQueuedChars;
    Integer fileBufferBytes;
    Integer maxOpenFiles;
    Integer maxFiles;
    Long flushIntervalMillis;
    Boolean eagerWakeup;
    Duration closeTimeout;
    Boolean registerShutdownHook;
    WorkerPool workerPool;
    WarningHandler warningHandler;
    String lineSeparator;
    Integer syncStripes;
    Set<PosixFilePermission> filePermissions;
    boolean normalizeIds;

    EfficientLoggerBuilder(Path directory, String fileNamePattern, long maxFileSizeKb) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.fileNamePattern = Objects.requireNonNull(fileNamePattern, "fileNamePattern");
        this.maxFileSizeKb = checkRange("maxFileSizeKb", maxFileSizeKb, 1, Config.MAX_FILE_SIZE_KB_MAX);
    }

    /**
     * Sheds every message while the log directory's volume has less than {@code shedBelowBytes}
     * free, until it has at least {@code resumeAboveBytes} again; the gap is hysteresis, so a
     * volume hovering around the mark doesn't flip the logger on and off. Off by default. Free
     * space is read at most once a second (one system call), on a worker thread, or in sync mode
     * by the caller that finds the reading due. Shed messages are counted in
     * {@code stats().droppedDiskSpace()} and {@code droppedCount()}, with one warning when
     * shedding starts and one with the count when it ends. If the volume can't be queried,
     * nothing is shed and that is warned about once.
     *
     * <p>Process-wide: the thresholds are shared by every logger, the latest {@code build()} that
     * sets them wins, and a build that doesn't set them leaves them as they are. Loggers whose
     * directories are on one volume share its reading and shed together; each counts and reports
     * its own shed messages.
     *
     * @param shedBelowBytes at least 1
     * @param resumeAboveBytes at least {@code shedBelowBytes}
     */
    public EfficientLoggerBuilder minFreeDiskSpace(long shedBelowBytes, long resumeAboveBytes) {
        checkRange("minFreeDiskSpace shedBelowBytes", shedBelowBytes, 1, Long.MAX_VALUE);
        if (resumeAboveBytes < shedBelowBytes) {
            throw new IllegalArgumentException("minFreeDiskSpace resumeAboveBytes must be at least shedBelowBytes ("
                    + shedBelowBytes + "), was " + resumeAboveBytes);
        }
        this.minFreeDiskBytes = shedBelowBytes;
        this.resumeFreeDiskBytes = resumeAboveBytes;
        return this;
    }

    /**
     * Caps the logger's own output at this many chars of messages per second, as a leaky bucket
     * with a burst of one second's worth: a burst up to that size goes through, and over any
     * longer period the accepted messages average no more than the cap. Off by default. Messages
     * over the cap are dropped at once, counted in {@code stats().droppedRateLimit()} and
     * {@code droppedCount()}, and reported with one warning when dropping starts and one with the
     * count once it has stopped for a second. Costs the caller one CAS per message. A single
     * message larger than the burst is still accepted while the logger is under the cap; it then
     * holds the following messages back until its share of time has passed, up to a day. In
     * async mode a message is charged only once the queue has taken it, so one the queue refuses
     * costs nothing; in sync mode it is charged before it is written, so one that then fails to
     * write (an invalid id, a full file, an I/O error) stays charged.
     *
     * <p>Process-wide: one bucket for every logger, so the cap bounds the process's whole output,
     * not each logger's. The latest {@code build()} that sets it wins, from its next charge; a
     * build that doesn't set it leaves it as it is. Each logger counts and reports its own drops.
     *
     * @param charsPerSecond at least 1
     */
    public EfficientLoggerBuilder maxCharsPerSecond(long charsPerSecond) {
        this.maxCharsPerSecond = checkRange("maxCharsPerSecond", charsPerSecond, 1, Long.MAX_VALUE);
        return this;
    }

    /**
     * Number of workers, 1 to 16. Default 1. Each worker has its own queue and writes its own set of
     * files; a given ID is always handled by the same worker, so each file's lines stay in order.
     * The workers run on the threads of the logger's {@link WorkerPool}, spread over them, so this
     * is also the most threads that can write for this logger at once. Async only.
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
     * Maximum number of new files the loggers of this process will create between them, at least 1.
     * Unlimited until some build sets it. Once reached, messages for IDs that don't have a file yet
     * are dropped and counted (in {@code stats().droppedInvalidId()}), with one warning per logger;
     * such IDs are tried again once a second, so raising the cap lets them through. Reopening a file
     * that already exists doesn't count, so a bounded set of IDs never hits the limit. Set it when
     * IDs can come from untrusted input, so they can't fill the disk with files.
     *
     * <p>Process-wide: the latest {@code build()} that sets it wins, for every logger, and files
     * created so far stay counted. A build that doesn't set it leaves the cap as it is.
     */
    public EfficientLoggerBuilder maxFiles(int files) {
        this.maxFiles = checkRange("maxFiles", files, 1, Integer.MAX_VALUE);
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
     * The threads that write for this logger. Default {@link WorkerPool#shared()}, which every
     * logger in the JVM shares, so the number of logging threads stays bounded however many loggers
     * there are. Give a logger its own pool (see {@link WorkerPool#create}) to keep its threads to
     * itself, or to make them with a {@code ThreadFactory}. Async only.
     */
    public EfficientLoggerBuilder workerPool(WorkerPool pool) {
        this.workerPool = Objects.requireNonNull(pool, "workerPool");
        return this;
    }

    /** Receives the logger's own warnings. Default {@link WarningHandler#stderr()}. */
    public EfficientLoggerBuilder warningHandler(WarningHandler handler) {
        this.warningHandler = Objects.requireNonNull(handler, "warningHandler");
        return this;
    }

    /**
     * Creates new log files, and the directory if it makes it, with these POSIX permissions, so they
     * aren't readable by other users by default. For example
     * {@code PosixFilePermissions.fromString("rw-------")}; the directory then gets {@code rwx------}.
     * The process umask can still remove bits from these, never add any, so the result is no less
     * private than asked. Existing files and directories keep their own permissions. Has no effect
     * on a file system without POSIX permissions, such as on Windows, where new files follow the
     * directory's access rules.
     */
    public EfficientLoggerBuilder filePermissions(Set<PosixFilePermission> permissions) {
        Objects.requireNonNull(permissions, "filePermissions");
        EnumSet<PosixFilePermission> copy = EnumSet.noneOf(PosixFilePermission.class);
        copy.addAll(permissions); // throws NullPointerException on a null element
        this.filePermissions = copy;
        return this;
    }

    /**
     * Canonicalizes each ID before use, by Unicode NFC normalization then lower-casing, so IDs that
     * differ only by case or Unicode form share one file and one worker. Off by default. Turn it on
     * for a case-insensitive or normalization-insensitive file system (Windows, macOS), where such
     * IDs already share a file on disk: it stops two of them being written concurrently from
     * different workers with interleaved lines. It costs an allocation per {@code log} call, and
     * merges IDs that differ only by case (so {@code "Foo"} and {@code "foo"} become one file).
     */
    public EfficientLoggerBuilder normalizeIds() {
        this.normalizeIds = true;
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
        return build(new TestHooks(ProcessLimits.shared()));
    }

    EfficientLogger build(TestHooks hooks) {
        Config config = buildConfig();
        if (config.filePermissions != null && hooks.sinks == FileOutputStreamSink.FACTORY) {
            hooks.sinks = FileOutputStreamSink.withPermissions(config.filePermissions);
        }
        try {
            hooks.sinks.createDirectoryIfMissing(config.directory);
        } catch (IOException e) {
            throw new UncheckedIOException("can't create directory " + config.directory, e);
        }
        // The latest build sets the process-wide limits, for every logger: once the logger exists,
        // so a build that fails leaves them alone, and before it runs, with a first reading of the
        // volume under the new mark, so a logger built on a nearly full volume sheds from its
        // first message.
        if (config.synchronous) {
            SyncLogger logger = new SyncLogger(config, hooks);
            hooks.limits.apply(config);
            logger.readLimits();
            return logger;
        }
        AsyncLogger logger = new AsyncLogger(config, hooks);
        hooks.limits.apply(config);
        logger.readLimits();
        return logger.start();
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
