package org.example.efficientlogger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class WarningHandlerTest {

    @Test
    void stderrPrintsTheMessageAndTheCause() {
        String out = captureStderr(() -> WarningHandler.stderr().warn("disk full", new IOException("boom")));

        assertTrue(out.contains("[EfficientLogger] disk full"), out);
        assertTrue(out.contains("java.io.IOException: boom"), out);
    }

    @Test
    void stderrPrintsOnlyTheMessageWithoutACause() {
        String out = captureStderr(() -> WarningHandler.stderr().warn("queue full", null));

        assertTrue(out.strip().equals("[EfficientLogger] queue full"), out);
    }

    /**
     * A handler is called from several threads at once (several workers, or several callers in sync
     * mode), so the default one must keep each warning and its stack trace together (review D2).
     */
    @Test
    void stderrKeepsConcurrentWarningsWhole() {
        int threads = 8;
        int each = 200;
        String out = captureStderr(() -> {
            CountDownLatch start = new CountDownLatch(1);
            Thread[] callers = new Thread[threads];
            for (int t = 0; t < threads; t++) {
                int caller = t;
                callers[t] = new Thread(() -> {
                    awaitUninterruptibly(start);
                    WarningHandler handler = WarningHandler.stderr();
                    for (int i = 0; i < each; i++) {
                        handler.warn("warning " + caller + "-" + i, new IOException("cause " + caller + "-" + i));
                    }
                });
                callers[t].start();
            }
            start.countDown();
            for (Thread c : callers) {
                joinUninterruptibly(c);
            }
        });

        // Each warning is its header line followed at once by the first line of its cause's stack
        // trace. The handler holds the stream's lock across both, so nothing else can land between.
        String[] lines = out.split("\\R");
        int headers = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("[EfficientLogger] warning ")) {
                headers++;
                String id = lines[i].substring("[EfficientLogger] warning ".length());
                assertTrue(i + 1 < lines.length, "a header without its cause: " + lines[i]);
                assertEquals("java.io.IOException: cause " + id, lines[i + 1], "torn output at line " + i);
            }
        }
        assertEquals(threads * each, headers);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinUninterruptibly(Thread thread) {
        boolean interrupted = false;
        for (;;) {
            try {
                thread.join();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String captureStderr(Runnable action) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(UTF_8);
    }
}
