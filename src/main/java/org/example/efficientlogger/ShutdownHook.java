package org.example.efficientlogger;

import java.util.ArrayList;
import java.util.List;

/**
 * The one shutdown hook for every open asynchronous logger in the JVM. It is registered with the
 * runtime when the first logger that wants one starts, and removed when the last of them has
 * finished closing. At exit it closes them all at once: each logger drains on the pool's threads
 * while the hook waits for each in turn, so the hook takes at most the longest of their close
 * timeouts, not the sum.
 */
final class ShutdownHook {

    private static final Object LOCK = new Object();
    private static final List<AsyncLogger> LOGGERS = new ArrayList<>(); // guarded by LOCK
    private static Thread hook; // guarded by LOCK; null while no logger is registered

    private ShutdownHook() {}

    /** Closes {@code logger} at JVM exit. Does nothing if the JVM is already exiting. */
    static void register(AsyncLogger logger) {
        synchronized (LOCK) {
            if (hook == null) {
                Thread t = new Thread(ShutdownHook::closeAll, "efficient-logger-shutdown");
                try {
                    Runtime.getRuntime().addShutdownHook(t);
                } catch (IllegalStateException e) {
                    return; // the JVM is already shutting down
                }
                hook = t;
            }
            LOGGERS.add(logger);
        }
    }

    /** Forgets {@code logger}; once no logger is left the hook is removed from the runtime. */
    static void unregister(AsyncLogger logger) {
        synchronized (LOCK) {
            if (!LOGGERS.remove(logger) || !LOGGERS.isEmpty()) {
                return;
            }
            Thread t = hook;
            hook = null;
            try {
                Runtime.getRuntime().removeShutdownHook(t);
            } catch (IllegalStateException e) {
                // the JVM is shutting down, possibly running this very hook
            }
        }
    }

    /** For tests: the registered hook thread if {@code logger} is registered, else null. */
    static Thread hookFor(AsyncLogger logger) {
        synchronized (LOCK) {
            return LOGGERS.contains(logger) ? hook : null;
        }
    }

    /** The hook's body: stops every registered logger accepting messages, then waits for each to finish. */
    static void closeAll() {
        List<AsyncLogger> open;
        synchronized (LOCK) {
            open = new ArrayList<>(LOGGERS);
        }
        long start = System.nanoTime();
        for (AsyncLogger logger : open) {
            logger.beginClose();
        }
        for (AsyncLogger logger : open) {
            logger.awaitCloseAtExit(start);
        }
    }
}
