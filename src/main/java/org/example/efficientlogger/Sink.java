package org.example.efficientlogger;

import java.io.IOException;

/** An open file that bytes are appended to. Used by one thread at a time. */
interface Sink {

    /** Writes all of {@code b[off, off + len)}. */
    void write(byte[] b, int off, int len) throws IOException;

    void close() throws IOException;
}
