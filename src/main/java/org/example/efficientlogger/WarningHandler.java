package org.example.efficientlogger;

import java.io.PrintStream;

/**
 * Receives the logger's own warnings, such as dropped messages, invalid IDs and I/O errors.
 *
 * <p>It is called from a worker thread, or in synchronous mode from the logging thread after
 * its lock is released. Keep it quick. Anything it throws is ignored.
 */
@FunctionalInterface
public interface WarningHandler {

    /**
     * @param message what happened
     * @param causeOrNull the underlying exception, if there is one
     */
    void warn(String message, Throwable causeOrNull);

    /** Prints each warning, and its cause's stack trace, to {@code System.err}. The default. */
    static WarningHandler stderr() {
        return (message, cause) -> {
            PrintStream err = System.err;
            synchronized (err) {
                err.println("[EfficientLogger] " + message);
                if (cause != null) {
                    cause.printStackTrace(err);
                }
            }
        };
    }
}
