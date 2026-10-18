package org.example.efficientlogger;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Run in a child JVM by AsyncThreadsTest: logs messages and exits without calling close.
 *
 * <p>Arguments: directory, message count, and how to exit: {@code return} (main returns),
 * {@code exit} ({@code System.exit(0)}) or {@code nohook} (main returns, no shutdown hook).
 *
 * <p>The worker writes nothing until close starts, so whatever reaches the files was written
 * because the shutdown hook closed the logger.
 */
public final class ShutdownMain {

    public static void main(String[] args) {
        Path dir = Paths.get(args[0]);
        int count = Integer.parseInt(args[1]);
        String mode = args[2];

        AtomicReference<AsyncLogger> logger = new AtomicReference<>();
        TestHooks hooks = new TestHooks();
        hooks.beforeBatch = () -> {
            AsyncLogger l;
            while ((l = logger.get()) == null || !l.closing()) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
        };
        logger.set((AsyncLogger) EfficientLogger.builder(dir, "{id}.json")
                .registerShutdownHook(!mode.equals("nohook"))
                .build(hooks));

        int accepted = 0;
        for (int i = 0; i < count; i++) {
            if (logger.get().log("id" + (i % 10), "{\"n\":" + i + "}")) {
                accepted++;
            }
        }
        System.out.println("accepted=" + accepted);
        System.out.flush();
        if (mode.equals("exit")) {
            System.exit(0);
        }
        // The worker threads are daemons, so the JVM exits when main returns.
    }
}
