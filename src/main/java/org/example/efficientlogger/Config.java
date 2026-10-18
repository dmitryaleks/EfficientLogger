package org.example.efficientlogger;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadFactory;

/** Validated, immutable settings with defaults resolved, plus values derived from them. */
final class Config {

    static final int DEFAULT_WORKER_THREADS = 1;
    static final int WORKER_THREADS_MAX = 16;
    static final int DEFAULT_MAX_QUEUED_MESSAGES = 1 << 16;
    static final int QUEUED_MESSAGES_MAX = 1 << 23;
    static final long DEFAULT_MAX_QUEUED_CHARS = 16L << 20;
    static final long QUEUED_CHARS_MAX = (1L << 38) - 1;
    static final int DEFAULT_FILE_BUFFER_BYTES = 8192;
    static final int FILE_BUFFER_BYTES_MIN = 64;
    static final int FILE_BUFFER_BYTES_MAX = 16 << 20;
    static final int DEFAULT_MAX_OPEN_FILES = 1024;
    static final long DEFAULT_FLUSH_INTERVAL_MILLIS = 100;
    static final long FLUSH_INTERVAL_MILLIS_MAX = 60_000;
    static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(10);
    static final int DEFAULT_SYNC_STRIPES = 16;
    static final int SYNC_STRIPES_MAX = 256;
    static final int BATCH_SIZE_MAX = 256;

    // Both modes
    final Path directory; // absolute and normalized
    final FileNamePattern fileNamePattern;
    final int maxIdLength; // log() rejects longer IDs at once: they can't fit in a file name
    final boolean synchronous;
    final int fileBufferBytes;
    final int maxOpenFiles;
    final int maxFileEntries; // per FileTable: maxOpenFiles plus room for entries that hold no file
    final byte[] lineSeparator; // don't modify
    final WarningHandler warningHandler;
    final Duration closeTimeout;
    final Set<PosixFilePermission> filePermissions; // null: use the umask

    // Async mode (defaults in sync mode)
    final int workerThreads;
    final int maxQueuedMessages;
    final long maxQueuedChars;
    final long flushIntervalNanos;
    final boolean eagerWakeup; // also true when the flush interval is 0
    final boolean registerShutdownHook;
    final ThreadFactory threadFactory; // null means the default daemon threads

    // Derived (async mode)
    final int ringCapacity; // power of two >= maxQueuedMessages, so a ring claim never fails
    final int batchSize; // messages drained per batch
    final long wakeCount; // producers wake a timed-sleeping worker at this queue size...
    final long wakeChars; // ...or at this many queued chars
    final long lowWaterCount; // an overflow episode ends when the queue is back down to
    final long lowWaterChars; // both of these

    // Sync mode
    final int syncStripes;

    Config(EfficientLoggerBuilder b) {
        if (b.synchronous) {
            rejectAsyncOnlyOptions(b);
        } else if (b.syncStripes != null) {
            throw new IllegalArgumentException("syncStripes only applies with synchronous()");
        }

        if (b.directory.getFileSystem() != FileSystems.getDefault()) {
            throw new IllegalArgumentException("directory must be on the default file system: " + b.directory);
        }
        directory = b.directory.toAbsolutePath().normalize();
        fileNamePattern = FileNamePattern.parse(directory, b.fileNamePattern);
        maxIdLength = fileNamePattern.maxIdLength();
        synchronous = b.synchronous;
        fileBufferBytes = orDefault(b.fileBufferBytes, DEFAULT_FILE_BUFFER_BYTES);
        maxOpenFiles = orDefault(b.maxOpenFiles, DEFAULT_MAX_OPEN_FILES);
        maxFileEntries = (int) Math.min(Integer.MAX_VALUE, (long) maxOpenFiles + FileTable.EXTRA_ENTRIES);
        lineSeparator = (b.lineSeparator != null ? b.lineSeparator : "\n").getBytes(StandardCharsets.US_ASCII);
        warningHandler = b.warningHandler != null ? b.warningHandler : WarningHandler.stderr();
        closeTimeout = b.closeTimeout != null ? b.closeTimeout : DEFAULT_CLOSE_TIMEOUT;
        filePermissions = b.filePermissions;

        workerThreads = orDefault(b.workerThreads, DEFAULT_WORKER_THREADS);
        maxQueuedMessages = orDefault(b.maxQueuedMessages, DEFAULT_MAX_QUEUED_MESSAGES);
        maxQueuedChars = b.maxQueuedChars != null ? b.maxQueuedChars : DEFAULT_MAX_QUEUED_CHARS;
        long flushIntervalMillis = b.flushIntervalMillis != null ? b.flushIntervalMillis : DEFAULT_FLUSH_INTERVAL_MILLIS;
        flushIntervalNanos = Duration.ofMillis(flushIntervalMillis).toNanos();
        // With a 0 interval a timed sleep would spin, so the worker must sleep until woken.
        eagerWakeup = (b.eagerWakeup != null && b.eagerWakeup) || flushIntervalMillis == 0;
        registerShutdownHook = b.registerShutdownHook == null || b.registerShutdownHook;
        threadFactory = b.threadFactory;

        ringCapacity = nextPowerOfTwo(maxQueuedMessages);
        batchSize = Math.max(1, Math.min(maxQueuedMessages / 4, BATCH_SIZE_MAX));
        wakeCount = eagerWakeup ? 1 : Math.max(1, maxQueuedMessages / 4);
        wakeChars = eagerWakeup ? 1 : Math.max(1, maxQueuedChars / 4);
        lowWaterCount = maxQueuedMessages / 2;
        lowWaterChars = maxQueuedChars / 2;

        syncStripes = orDefault(b.syncStripes, DEFAULT_SYNC_STRIPES);
    }

    private static void rejectAsyncOnlyOptions(EfficientLoggerBuilder b) {
        List<String> set = new ArrayList<>();
        if (b.workerThreads != null) set.add("workerThreads");
        if (b.maxQueuedMessages != null) set.add("maxQueuedMessages");
        if (b.maxQueuedChars != null) set.add("maxQueuedChars");
        if (b.flushIntervalMillis != null) set.add("flushIntervalMillis");
        if (b.eagerWakeup != null) set.add("eagerWakeup");
        if (b.registerShutdownHook != null) set.add("registerShutdownHook");
        if (b.threadFactory != null) set.add("threadFactory");
        if (!set.isEmpty()) {
            throw new IllegalArgumentException(
                    "synchronous() can't be combined with async-only options: " + String.join(", ", set));
        }
    }

    private static int orDefault(Integer value, int defaultValue) {
        return value != null ? value : defaultValue;
    }

    static int nextPowerOfTwo(int n) {
        return n <= 1 ? 1 : Integer.highestOneBit(n - 1) << 1;
    }
}
