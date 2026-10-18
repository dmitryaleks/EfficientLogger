package org.example.efficientlogger;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The files one owner writes to: an async worker, or a sync-mode stripe. Single-threaded. Only the
 * counters may be read from other threads.
 *
 * <p>Two ways to write, one per mode. {@link #append} buffers records per file, and the owner calls
 * {@link #flushDirty} to hand them to the OS. {@link #writeThrough} writes each record at once.
 * Either way a {@code write} call carries whole records, so a crash can't leave half a record
 * followed by the next one. Only a record larger than the buffer is split.
 *
 * <p>Warnings are queued rather than emitted, so the owner can emit them outside any lock:
 * see {@link #takeWarnings}.
 *
 * <p>Allocation happens only when a file is opened or closed, when buffers are first needed, and
 * when something goes wrong.
 */
final class FileTable {

    static final long RETRY_NANOS = TimeUnit.SECONDS.toNanos(1);
    /** Closing a file used this recently to stay under maxOpenFiles means the cap is too low. */
    static final long THRASH_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(1);
    /** Room for entries that hold no file (invalid IDs, failed files) beyond maxOpenFiles. */
    static final int EXTRA_ENTRIES = 1024;

    private static final VarHandle LONGS =
            MethodHandles.arrayElementVarHandle(long[].class).withInvokeExactBehavior();
    private static final int WRITTEN = 0;
    private static final int LOST_IO_ERROR = 1;
    private static final int DROPPED_INVALID_ID = 2;

    private final FileNamePattern pattern;
    private final byte[] separator;
    private final int bufferBytes;
    private final int maxEntries;
    private final SinkFactory sinks;
    private final OpenFileBudget budget;
    private final FileCreationBudget fileBudget;
    private final Warnings warnings;

    private final HashMap<String, FileState> files;
    private FileState lruHead; // most recently used
    private FileState lruTail; // least recently used
    private int openCount;
    private String lastId; // 1-entry cache for runs of records to the same file
    private FileState lastFile;

    private FileState[] dirty = new FileState[16]; // files with buffered records
    private int dirtyCount;
    private long firstDirtyNanos;
    private byte[][] pool = new byte[16][];
    private int pooled;
    private byte[] scratch; // write-through mode

    // Written by the owner with opaque stores, so other threads can read them without tearing.
    private final long[] counters = new long[3];
    private List<Warnings.Pending> pendingWarnings;

    FileTable(FileNamePattern pattern, byte[] separator, int bufferBytes, int maxEntries,
            SinkFactory sinks, OpenFileBudget budget, FileCreationBudget fileBudget, Warnings warnings) {
        this.pattern = pattern;
        this.separator = separator;
        this.bufferBytes = bufferBytes;
        this.maxEntries = maxEntries;
        this.sinks = sinks;
        this.budget = budget;
        this.fileBudget = fileBudget;
        this.warnings = warnings;
        this.files = new HashMap<>(Math.min(maxEntries, 4096) * 4 / 3 + 1);
    }

    // ---- buffered mode (async workers) ----

    /** Adds a record to its file's buffer, writing the buffer out first if the record doesn't fit. */
    void append(String id, String json, long now) {
        FileState f = id == lastId ? lastFile : lookup(id, now);
        touch(f, now);
        if (f.state != FileState.OPEN && !reopenIfDue(f, now)) {
            dropFor(f);
            return;
        }
        byte[] b = f.buf;
        if (b == null) {
            b = f.buf = takeBuffer();
        }
        int start = f.pos;
        int limit = b.length - separator.length;
        long r = 0;
        boolean fits = false;
        if (start <= limit) { // past it, the buffer is too full for even an empty record
            r = Utf8.encode(json, 0, b, start, limit);
            fits = Utf8.nextChar(r) == json.length();
        }
        if (!fits) {
            // Write out the complete records before it, then encode it from offset 0.
            f.pos = start;
            if (start > 0 && !writeOut(f, now)) {
                add(LOST_IO_ERROR, 1);
                return;
            }
            r = Utf8.encode(json, 0, b, 0, limit);
            while (Utf8.nextChar(r) != json.length()) {
                // Larger than the whole buffer: the one case where a record is split across writes.
                f.pos = Utf8.endPos(r);
                if (!writeOut(f, now)) {
                    add(LOST_IO_ERROR, 1);
                    return;
                }
                r = Utf8.encode(json, Utf8.nextChar(r), b, 0, limit);
            }
        }
        f.pos = putSeparator(b, Utf8.endPos(r));
        f.pendingRecords++;
        markDirty(f, now);
    }

    /** Hands every buffered record to the OS. */
    void flushDirty(long now) {
        while (dirtyCount > 0) {
            flushFile(dirty[dirtyCount - 1], now); // always removes that file from the dirty array
        }
    }

    /** Number of files with buffered records. */
    int dirtyCount() {
        return dirtyCount;
    }

    /** When the oldest buffered record still waiting was added. Meaningful only when dirtyCount > 0. */
    long firstDirtyNanos() {
        return firstDirtyNanos;
    }

    // ---- write-through mode (sync stripes) ----

    /**
     * Writes a record to its file in one {@code write} call (several if it's larger than the
     * buffer). False if it was dropped.
     */
    boolean writeThrough(String id, String json, long now) {
        FileState f = id == lastId ? lastFile : lookup(id, now);
        touch(f, now);
        if (f.state != FileState.OPEN && !reopenIfDue(f, now)) {
            dropFor(f);
            return false;
        }
        byte[] b = scratch;
        if (b == null) {
            b = scratch = new byte[bufferBytes];
        }
        int limit = b.length - separator.length;
        long r = Utf8.encode(json, 0, b, 0, limit);
        while (Utf8.nextChar(r) != json.length()) {
            if (!writeRaw(f, b, Utf8.endPos(r), now)) {
                add(LOST_IO_ERROR, 1);
                return false;
            }
            r = Utf8.encode(json, Utf8.nextChar(r), b, 0, limit);
        }
        if (!writeRaw(f, b, putSeparator(b, Utf8.endPos(r)), now)) {
            add(LOST_IO_ERROR, 1);
            return false;
        }
        add(WRITTEN, 1);
        return true;
    }

    // ---- both modes ----

    /** Writes out every buffered record and closes every file. Files reopen if used again. */
    void closeAll(long now) {
        flushDirty(now);
        for (FileState f = lruHead; f != null; f = f.lruNext) {
            if (f.state == FileState.OPEN) {
                Exception e = closeSink(f);
                if (e != null) {
                    reportIoError("can't close " + f.path, e, now);
                }
            }
        }
        files.clear();
        lruHead = null;
        lruTail = null;
        lastId = null;
        lastFile = null;
    }

    /** The warnings queued since the last call, or null if there are none. */
    List<Warnings.Pending> takeWarnings() {
        List<Warnings.Pending> taken = pendingWarnings;
        pendingWarnings = null;
        return taken;
    }

    /** Records handed to the OS. */
    long written() {
        return (long) LONGS.getOpaque(counters, WRITTEN);
    }

    /** Records lost to I/O errors, including those dropped while a failed file waits to retry. */
    long lostIoError() {
        return (long) LONGS.getOpaque(counters, LOST_IO_ERROR);
    }

    /** Counts records the owner lost to an unexpected error. */
    void countLost(long records) {
        add(LOST_IO_ERROR, records);
    }

    /** Records dropped because their ID can't be a file name. */
    long droppedInvalidId() {
        return (long) LONGS.getOpaque(counters, DROPPED_INVALID_ID);
    }

    int openCount() {
        return openCount;
    }

    int entryCount() {
        return files.size();
    }

    int pooledBuffers() {
        return pooled;
    }

    // ---- lookup, open, evict ----

    private FileState lookup(String id, long now) {
        FileState f = files.get(id);
        if (f == null) {
            f = create(id, now);
        }
        lastId = id;
        lastFile = f;
        return f;
    }

    private FileState create(String id, long now) {
        while (files.size() >= maxEntries) {
            evict(lruTail, now);
        }
        FileState f;
        String reason = pattern.invalidReason(id);
        if (reason != null) {
            f = new FileState(id, null);
            reportInvalidId(id, reason);
        } else {
            f = new FileState(id, pattern.path(id));
            open(f, now);
        }
        files.put(id, f);
        linkFront(f);
        return f;
    }

    private boolean reopenIfDue(FileState f, long now) {
        if (f.state != FileState.FAILED || now - f.retryAtNanos < 0) {
            return false;
        }
        open(f, now);
        return f.state == FileState.OPEN;
    }

    /** Opens f's file. Afterwards f is OPEN, or FAILED with a retry time. */
    private void open(FileState f, long now) {
        if (budget.acquire()) {
            while (budget.isOver() && evictLeastRecentlyUsedOpen(now, true)) {
                // close our own files until back under the cap, or none are left
            }
        }
        Sink sink = null;
        try {
            int last = sinks.lastByte(f.path);
            if (last < 0 && !fileBudget.tryCreate()) {
                // The file doesn't exist yet and the logger is at its maxFiles limit: don't create
                // another. Reopening a file that already exists (last >= 0) is always allowed.
                budget.release();
                f.state = FileState.CAPPED;
                reportMaxFilesReached();
                return;
            }
            sink = openSink(f.path, now);
            if (last >= 0 && last != '\n') {
                // The file ends mid-line, e.g. after a crash: finish that line so ours starts clean.
                sink.write(separator, 0, separator.length);
            }
            f.sink = sink;
            f.state = FileState.OPEN;
            openCount++;
        } catch (IOException | RuntimeException e) { // see writeRaw
            if (sink != null) {
                closeQuietly(sink);
            }
            budget.release();
            f.state = FileState.FAILED;
            f.retryAtNanos = now + RETRY_NANOS;
            reportIoError("can't open " + f.path + "; dropping its messages for 1 s", e, now);
        }
    }

    private Sink openSink(Path path, long now) throws IOException {
        try {
            return sinks.open(path);
        } catch (IOException e) {
            if (isTooManyOpenFiles(e)) {
                // The OS limit is lower than maxOpenFiles: close half our files and try once more.
                int target = Math.max(1, openCount / 2);
                int closed = 0;
                while (closed < target && evictLeastRecentlyUsedOpen(now, false)) {
                    closed++;
                }
                if (closed == 0) {
                    throw e;
                }
                reportIoError("too many open files; closed " + closed + " least recently used files", e, now);
                return sinks.open(path);
            }
            if (sinks.createDirectoryIfMissing(pattern.directory())) {
                return sinks.open(path);
            }
            throw e;
        }
    }

    /** EMFILE has no exception type of its own, so this goes by the message: Unix, then Windows. */
    private static boolean isTooManyOpenFiles(IOException e) {
        String message = e.getMessage();
        return message != null
                && (message.contains("Too many open files") || message.contains("The system cannot open the file"));
    }

    /** Closes and forgets our least recently used open file. False if we have none open. */
    private boolean evictLeastRecentlyUsedOpen(long now, boolean overBudget) {
        for (FileState f = lruTail; f != null; f = f.lruPrev) {
            if (f.state == FileState.OPEN) {
                if (overBudget && now - f.lastUsedNanos < THRASH_WINDOW_NANOS && warnings.admitThrashReport()) {
                    warn("more than maxOpenFiles=" + budget.max() + " files are in use at once, so files are"
                            + " being closed and reopened; consider raising maxOpenFiles", null);
                }
                evict(f, now);
                return true;
            }
        }
        return false;
    }

    /** Removes f from the table, first writing out its records and closing its file. */
    private void evict(FileState f, long now) {
        if (f.state == FileState.OPEN) {
            flushFile(f, now); // on a write error this fails f, which closes it
            if (f.state == FileState.OPEN) {
                Exception e = closeSink(f);
                if (e != null) {
                    reportIoError("can't close " + f.path, e, now);
                }
            }
        }
        unlink(f);
        files.remove(f.id);
        if (lastFile == f) {
            lastId = null;
            lastFile = null;
        }
    }

    // ---- writing ----

    /** Writes the buffer out and empties it. False if that failed, in which case f is now FAILED. */
    private boolean writeOut(FileState f, long now) {
        if (!writeRaw(f, f.buf, f.pos, now)) {
            return false;
        }
        add(WRITTEN, f.pendingRecords);
        f.pos = 0;
        f.pendingRecords = 0;
        return true;
    }

    private boolean writeRaw(FileState f, byte[] b, int len, long now) {
        try {
            f.sink.write(b, 0, len);
            return true;
        } catch (IOException | RuntimeException e) {
            // A sink that throws unchecked is broken just the same. Failing the file keeps one bad
            // file from stalling the others, and retries it later.
            fail(f, "can't write to " + f.path, e, now);
            return false;
        }
    }

    private void flushFile(FileState f, long now) {
        if (f.pos > 0 && !writeOut(f, now)) {
            return; // fail() already released the buffer and the dirty slot
        }
        releaseBuffer(f);
        removeDirty(f);
    }

    /** Drops f's buffered records, closes it, and backs off before reopening. */
    private void fail(FileState f, String what, Exception e, long now) {
        add(LOST_IO_ERROR, f.pendingRecords);
        f.pos = 0;
        f.pendingRecords = 0;
        releaseBuffer(f);
        removeDirty(f);
        closeSink(f); // a close error adds nothing to the error being reported
        f.state = FileState.FAILED;
        f.retryAtNanos = now + RETRY_NANOS;
        reportIoError(what + "; dropping its messages for 1 s", e, now);
    }

    private Exception closeSink(FileState f) {
        Sink sink = f.sink;
        f.sink = null;
        openCount--;
        budget.release();
        try {
            sink.close();
            return null;
        } catch (IOException | RuntimeException e) {
            return e;
        }
    }

    private static void closeQuietly(Sink sink) {
        try {
            sink.close();
        } catch (IOException | RuntimeException ignored) {
            // already reporting the error that made us close it
        }
    }

    private int putSeparator(byte[] b, int p) {
        for (byte s : separator) {
            b[p++] = s;
        }
        return p;
    }

    private void dropFor(FileState f) {
        boolean noFile = f.state == FileState.INVALID || f.state == FileState.CAPPED;
        add(noFile ? DROPPED_INVALID_ID : LOST_IO_ERROR, 1);
    }

    private void add(int counter, long n) {
        LONGS.setOpaque(counters, counter, counters[counter] + n);
    }

    // ---- buffers and the dirty array ----

    private byte[] takeBuffer() {
        if (pooled == 0) {
            return new byte[bufferBytes];
        }
        byte[] b = pool[--pooled];
        pool[pooled] = null;
        return b;
    }

    private void releaseBuffer(FileState f) {
        byte[] b = f.buf;
        if (b == null) {
            return;
        }
        f.buf = null;
        if (pooled == pool.length) {
            pool = Arrays.copyOf(pool, pooled * 2);
        }
        pool[pooled++] = b;
    }

    private void markDirty(FileState f, long now) {
        if (f.dirtyIdx >= 0) {
            return;
        }
        if (dirtyCount == 0) {
            firstDirtyNanos = now;
        }
        if (dirtyCount == dirty.length) {
            dirty = Arrays.copyOf(dirty, dirtyCount * 2);
        }
        f.dirtyIdx = dirtyCount;
        dirty[dirtyCount++] = f;
    }

    private void removeDirty(FileState f) {
        int i = f.dirtyIdx;
        if (i < 0) {
            return;
        }
        FileState last = dirty[--dirtyCount];
        dirty[i] = last;
        last.dirtyIdx = i;
        dirty[dirtyCount] = null;
        f.dirtyIdx = -1;
    }

    // ---- LRU list ----

    private void touch(FileState f, long now) {
        f.lastUsedNanos = now;
        if (f != lruHead) {
            unlink(f);
            linkFront(f);
        }
    }

    private void linkFront(FileState f) {
        f.lruPrev = null;
        f.lruNext = lruHead;
        if (lruHead != null) {
            lruHead.lruPrev = f;
        } else {
            lruTail = f;
        }
        lruHead = f;
    }

    private void unlink(FileState f) {
        if (f.lruPrev != null) {
            f.lruPrev.lruNext = f.lruNext;
        } else {
            lruHead = f.lruNext;
        }
        if (f.lruNext != null) {
            f.lruNext.lruPrev = f.lruPrev;
        } else {
            lruTail = f.lruPrev;
        }
        f.lruPrev = null;
        f.lruNext = null;
    }

    // ---- warnings ----

    private void reportMaxFilesReached() {
        if (warnings.admitMaxFilesReport()) {
            warn("the logger has reached its limit of " + fileBudget.max() + " files (maxFiles); dropping"
                    + " messages for ids without a file yet (counted in stats().droppedInvalidId())", null);
        }
    }

    private void reportInvalidId(String id, String reason) {
        int n = warnings.admitInvalidIdReport();
        if (n == 0) {
            return;
        }
        String message = "dropping messages for invalid id " + FileNamePattern.quote(id) + ": " + reason;
        if (n == Warnings.MAX_INVALID_ID_REPORTS) {
            message += " (further invalid ids won't be reported)";
        }
        warn(message, null);
    }

    private void reportIoError(String message, Exception e, long now) {
        long suppressed = warnings.admitIoErrorReport(now);
        if (suppressed < 0) {
            return;
        }
        if (suppressed > 0) {
            message += " (" + suppressed + " more I/O errors were not reported)";
        }
        warn(message, e);
    }

    private void warn(String message, Throwable cause) {
        if (pendingWarnings == null) {
            pendingWarnings = new ArrayList<>(4);
        }
        pendingWarnings.add(new Warnings.Pending(message, cause));
    }
}
