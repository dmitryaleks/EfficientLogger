package org.example.efficientlogger;

import java.nio.file.Path;

/** One ID's file, as tracked by its {@link FileTable}. Only that table's thread touches it. */
final class FileState {

    static final byte OPEN = 0;
    static final byte FAILED = 1; // not open; retry opening at retryAtNanos
    static final byte INVALID = 2; // the ID can't be used as a file name
    static final byte CAPPED = 3; // no file: the logger is at its maxFiles limit

    final String id;
    final Path path; // null when INVALID
    byte state;
    Sink sink; // non-null only when OPEN
    byte[] buf; // buffered mode: held only while the file has unwritten records
    int pos; // bytes used in buf
    int pendingRecords; // complete records in buf
    int dirtyIdx = -1; // index in the table's dirty array, or -1
    FileState lruPrev; // towards the most recently used
    FileState lruNext; // towards the least recently used
    long lastUsedNanos;
    long retryAtNanos;

    FileState(String id, Path path) {
        this.id = id;
        this.path = path;
        this.state = path == null ? INVALID : FAILED; // FAILED until opened
    }
}
