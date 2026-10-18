package org.example.efficientlogger;

import java.io.PrintStream;

/**
 * Receives the logger's own warnings, such as dropped messages, invalid IDs and I/O errors.
 *
 * <p>It is called inline from a worker thread, or in synchronous mode from the logging thread
 * after its lock is released, so it must return promptly and must not block. A handler that
 * blocks (for example writing to a pipe nobody reads) stalls that thread, and in async mode holds
 * up writing. The logger rate-limits its own warnings, so a handler is called at most occasionally.
 * Anything it throws is ignored.
 *
 * <p>It must be thread-safe: with several worker threads, or several threads logging in
 * synchronous mode, it can be called by more than one thread at once. The default handler
 * serializes its output on {@code System.err}, so each warning and its stack trace come out whole.
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
