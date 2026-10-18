package org.example.efficientlogger;

import java.io.IOException;
import java.nio.file.Path;

/** The logger's only contact with the file system, so tests can replace it. */
interface SinkFactory {

    /**
     * Opens {@code path} for appending, creating the file if needed. Must refuse anything that is
     * not a regular file (a symlink, FIFO, device or directory), so a file planted at the path
     * can't redirect the writes or block the thread.
     */
    Sink open(Path path) throws IOException;

    /**
     * The last byte of {@code path} (0-255), or -1 if the file is empty or doesn't exist. Must
     * refuse, rather than read, anything that is not a regular file.
     */
    int lastByte(Path path) throws IOException;

    /** Creates {@code dir} and its parents if it doesn't exist. True if it was missing. */
    boolean createDirectoryIfMissing(Path dir) throws IOException;

    /**
     * A reason the log directory is unsafe to write in, such as being writable by other users who
     * could then redirect or read the logs, or null if it looks fine. Checked once when the logger
     * is built; only advisory.
     */
    default String directoryExposedReason(Path directory) {
        return null;
    }
}
