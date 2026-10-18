package org.example.efficientlogger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
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
