package org.example.efficientlogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** The logger's only contact with the file system, so tests can replace it. */
interface SinkFactory {

    /** {@link #lastByte} result: the file doesn't exist, so opening it will create it. */
    int MISSING = -1;
    /** {@link #lastByte} result: the file exists but is empty. */
    int EMPTY = -2;

    /**
     * Opens {@code path} for appending, creating the file if needed. Must refuse anything that is
     * not a regular file (a symlink, FIFO, device or directory), so a file planted at the path
     * can't redirect the writes or block the thread.
     */
    Sink open(Path path) throws IOException;

    /**
     * The last byte of {@code path} (0-255), {@link #EMPTY} if the file is empty, or
     * {@link #MISSING} if it doesn't exist. Must refuse, rather than read, anything that is not a
     * regular file.
     */
    int lastByte(Path path) throws IOException;

    /**
     * The size of {@code path} in bytes, or 0 if it doesn't exist. Must refuse, rather than
     * measure, anything that is not a regular file. Called when a file is opened and, for a file
     * that has reached maxFileSizeKb, once a second while messages for it arrive.
     */
    default long size(Path path) throws IOException {
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return 0;
        }
        if (!attrs.isRegularFile()) {
            throw new IOException("refusing to use " + path + ": not a regular file");
        }
        return attrs.size();
    }

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
