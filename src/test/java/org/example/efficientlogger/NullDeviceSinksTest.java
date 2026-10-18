package org.example.efficientlogger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The benchmark's null-device sink (in {@code src/jmh}) bypasses the library's regular-file check
 * on purpose, so it has to be checked against the library as that check evolves. Once, the check
 * refused the device, and every EfficientLogger benchmark run lost every message (review R4).
 */
class NullDeviceSinksTest {

    @TempDir
    Path dir;

    @Test
    void opensWritesAndClosesTheNullDevice() throws IOException {
        NullDeviceSinks sinks = new NullDeviceSinks();
        Path file = dir.resolve("a.json");
        Sink sink = sinks.open(file);
        byte[] line = "{}\n".getBytes(UTF_8);
        sink.write(line, 0, line.length);
        sink.close();

        assertEquals(SinkFactory.MISSING, sinks.lastByte(file));
        assertFalse(Files.exists(file), "nothing is written at the named path");
    }

    @ParameterizedTest(name = "synchronous = {0}")
    @ValueSource(booleans = {false, true})
    void aLoggerOnTheNullDeviceLosesNothing(boolean synchronous) {
        List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        TestHooks hooks = new TestHooks();
        hooks.sinks = new NullDeviceSinks();
        EfficientLoggerBuilder b = EfficientLogger.builder(dir, "{id}.json")
                .warningHandler((message, cause) -> warnings.add(cause == null ? message : message + " | " + cause));
        if (synchronous) {
            b.synchronous();
        }
        int count = 1000;
        EfficientLogger logger = b.build(hooks);
        boolean closed;
        try {
            for (int i = 0; i < count; i++) {
                assertTrue(logger.log("user-" + (i % 50), "{\"n\":" + i + "}"));
            }
        } finally {
            closed = logger.close(Duration.ofSeconds(10));
        }
        assertTrue(closed, "everything was written");

        LoggerStats s = logger.stats();
        assertEquals(count, s.accepted(), s::toString);
        assertEquals(count, s.written(), s::toString);
        assertEquals(0, s.lostIoError(), s::toString);
        assertEquals(0, s.droppedInvalidId(), s::toString);
        assertTrue(warnings.isEmpty(), warnings::toString);
    }
}
