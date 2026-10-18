package org.example.efficientlogger;

import java.io.IOException;
import java.nio.file.Path;

/** The logger's only contact with the file system, so tests can replace it. */
interface SinkFactory {

    /** Opens {@code path} for appending, creating the file if needed. */
    Sink open(Path path) throws IOException;

    /** The last byte of {@code path} (0-255), or -1 if the file is empty or doesn't exist. */
    int lastByte(Path path) throws IOException;

    /** Creates {@code dir} and its parents if it doesn't exist. True if it was missing. */
    boolean createDirectoryIfMissing(Path dir) throws IOException;
}
