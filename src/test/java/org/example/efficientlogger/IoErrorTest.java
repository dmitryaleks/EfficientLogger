package org.example.efficientlogger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * I/O errors end to end, in both modes: what is lost, what is reported, and how the logger
 * recovers. Async loggers run their workers by hand on a fake clock, with a 0 ms flush interval,
 * so each step hands its records to the OS before the test looks.
 */
class IoErrorTest {

    enum Mode { SYNC, ASYNC }

    private static final long SECOND = 1_000_000_000L;

    @TempDir
    Path tempDir;

    private final Path fakeDir = Paths.get("fake-io-logs").toAbsolutePath().normalize();
    private final FakeSinks sinks = new FakeSinks(fakeDir);
    private final long[] now = {0};
    private final List<String> warnings = new ArrayList<>();
    private Mode mode;
    private EfficientLogger logger;
    private boolean closed;

    @AfterEach
    void closeIfStillOpen() {
        if (logger != null && !closed) {
            close(); // real files must be closed before @TempDir deletes them on Windows
        }
    }

    // ---- writing ----

    @ParameterizedTest
    @EnumSource(Mode.class)
    void aFullDiskLosesTheMessageAndTheNextLineStartsClean(Mode mode) {
        start(mode, fakeDir, sinks, b -> {});
        Path a = fakeDir.resolve("a.json");
        assertTrue(log("a", json(1)));
        sinks.failNextWriteAfter(a, 4, new IOException("No space left on device")); // only {"n" fits
        assertEquals(mode == Mode.ASYNC, log("a", json(2)), "sync mode reports the loss");
        assertEquals(List.of("can't write to " + a + "; dropping its messages for 1 s | No space left on device"),
                warnings);

        assertEquals(mode == Mode.ASYNC, log("a", json(3)), "dropped while waiting to retry");
        now[0] += SECOND;
        assertTrue(log("a", json(4)));
        close();

        assertEquals(json(1) + "\n{\"n\"\n" + json(4) + "\n", sinks.content(a), "the torn line is ended first");
        assertStats(4, 2, 2);
        assertEquals(1, warnings.size(), warnings::toString);
    }

    /** An exception from a sink that isn't an IOException is handled the same way; it never reaches log(). */
    @ParameterizedTest
    @EnumSource(Mode.class)
    void aSinkThatThrowsUncheckedFailsOnlyItsFile(Mode mode) {
        start(mode, fakeDir, sinks, b -> {});
        Path a = fakeDir.resolve("a.json");
        sinks.failNextWrite(a, new IllegalStateException("bug"));
        assertEquals(mode == Mode.ASYNC, log("a", json(1)));
        assertTrue(log("b", json(2)));
        close();

        assertEquals(List.of("can't write to " + a + "; dropping its messages for 1 s | bug"), warnings);
        assertEquals(json(2) + "\n", sinks.content(fakeDir.resolve("b.json")));
        assertStats(2, 1, 1);
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void aFailedCloseIsReported(Mode mode) {
        start(mode, fakeDir, sinks, b -> {});
        Path a = fakeDir.resolve("a.json");
        log("a", json(1));
        sinks.failNextClose(a, new IOException("close failed"));
        close();

        assertEquals(List.of("can't close " + a + " | close failed"), warnings);
        assertEquals(json(1) + "\n", sinks.content(a));
        assertStats(1, 1, 0);
    }

    // ---- opening ----

    @ParameterizedTest
    @EnumSource(Mode.class)
    void aFileThatCantBeOpenedIsRetriedASecondLater(Mode mode) throws IOException {
        Path x = Files.createDirectory(tempDir.resolve("x.json")); // a directory has the file's name
        start(mode, tempDir, FileOutputStreamSink.FACTORY, b -> {});
        assertEquals(mode == Mode.ASYNC, log("x", json(1)));
        assertTrue(log("y", json(2)));
        assertEquals(1, warnings.size(), warnings::toString);
        assertTrue(warnings.get(0).startsWith("can't open " + x + "; dropping its messages for 1 s | "), warnings.get(0));

        assertEquals(mode == Mode.ASYNC, log("x", json(3)), "not retried yet");
        Files.delete(x);
        now[0] += SECOND;
        assertTrue(log("x", json(4)));
        close();

        assertEquals(List.of(json(4)), Files.readAllLines(x, UTF_8));
        assertEquals(List.of(json(2)), Files.readAllLines(tempDir.resolve("y.json"), UTF_8));
        assertStats(4, 2, 2);
        assertEquals(1, warnings.size(), warnings::toString);
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void aDeletedDirectoryIsCreatedAgain(Mode mode) throws IOException {
        Path dir = tempDir.resolve("logs");
        start(mode, dir, FileOutputStreamSink.FACTORY, b -> {});
        Files.delete(dir); // after build created it
        assertTrue(log("a", json(1)));
        close();

        assertEquals(List.of(json(1)), Files.readAllLines(dir.resolve("a.json"), UTF_8));
        assertTrue(warnings.isEmpty(), warnings::toString);
    }

    /** Deleted while the logger runs, as {@code rm -r} does on Unix (Windows won't delete open files). */
    @ParameterizedTest
    @EnumSource(Mode.class)
    void aDirectoryDeletedWhileRunningIsCreatedAgainWhenAFileIsNextOpened(Mode mode) {
        start(mode, fakeDir, sinks, b -> oneOwner(mode, b.maxOpenFiles(1)));
        log("a", json(1));
        now[0] += 2 * SECOND; // a is idle by now, so closing it isn't thrashing
        log("b", json(1)); // closes a, to stay within maxOpenFiles
        sinks.deleteDirectory(fakeDir);
        now[0] += 2 * SECOND;

        assertTrue(log("a", json(2)));
        close();
        assertTrue(sinks.directoryExists(fakeDir));
        assertEquals(json(2) + "\n", sinks.content(fakeDir.resolve("a.json")));
        assertTrue(warnings.isEmpty(), warnings::toString);
    }

    /** The OS limit is below maxOpenFiles: files are closed to make room, and nothing is lost. */
    @ParameterizedTest
    @EnumSource(Mode.class)
    void runningOutOfFileDescriptorsClosesFilesAndLosesNothing(Mode mode) {
        sinks.osOpenLimit = 4;
        start(mode, fakeDir, sinks, b -> oneOwner(mode, b));
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 10; i++) {
                assertTrue(log("f" + i, json(round)));
            }
        }
        close();

        for (int i = 0; i < 10; i++) {
            assertEquals(json(0) + "\n" + json(1) + "\n" + json(2) + "\n", sinks.content(fakeDir.resolve("f" + i + ".json")));
        }
        assertTrue(sinks.maxOpenNow <= 4, "max open " + sinks.maxOpenNow);
        assertStats(30, 30, 0);
        assertTrue(warnings.get(0).startsWith("too many open files; closed 2 least recently used files | "),
                warnings.get(0));
        for (String w : warnings.subList(1, warnings.size())) {
            assertTrue(w.matches("\\d+ more I/O errors? (was|were) not reported"), w); // the rest, at close
        }
    }

    // ---- reporting ----

    @ParameterizedTest
    @EnumSource(Mode.class)
    void errorsAreReportedAtMostOnceEvery10SecondsAcrossTheLogger(Mode mode) {
        start(mode, fakeDir, sinks, b -> {
            if (mode == Mode.ASYNC) {
                b.workerThreads(3); // sync mode has 16 stripes
            }
        });
        for (int i = 0; i < 30; i++) {
            failOpen("f" + i);
            log("f" + i, "{}");
        }
        assertEquals(1, warnings.size(), warnings::toString);

        now[0] += 10 * SECOND;
        failOpen("g");
        log("g", "{}");
        assertEquals(2, warnings.size(), warnings::toString);
        assertEquals("can't open " + fakeDir.resolve("g.json") + "; dropping its messages for 1 s"
                + " (29 more I/O errors were not reported) | denied", warnings.get(1));

        for (int i = 0; i < 5; i++) {
            failOpen("h" + i);
            log("h" + i, "{}");
        }
        assertEquals(2, warnings.size(), warnings::toString);
        close();
        assertEquals(List.of("5 more I/O errors were not reported"), warnings.subList(2, warnings.size()),
                "reported at close, once");
        assertStats(36, 0, 36);
    }

    // ---- helpers ----

    private void start(Mode mode, Path dir, SinkFactory sinkFactory, Consumer<EfficientLoggerBuilder> options) {
        this.mode = mode;
        EfficientLoggerBuilder b = EfficientLogger.builder(dir, "{id}.json")
                .warningHandler((message, cause) -> warnings.add(cause == null ? message : message + " | " + cause.getMessage()));
        if (mode == Mode.SYNC) {
            b.synchronous();
        } else {
            b.flushIntervalMillis(0);
        }
        options.accept(b);
        TestHooks hooks = new TestHooks();
        hooks.sinks = sinkFactory;
        hooks.nanoClock = () -> now[0];
        hooks.startThreads = false;
        logger = b.build(hooks);
    }

    /** Every ID handled by one FileTable, so its LRU sees all files. */
    private static void oneOwner(Mode mode, EfficientLoggerBuilder b) {
        if (mode == Mode.SYNC) {
            b.syncStripes(1);
        }
    }

    /** Logs, and in async mode lets the workers write it out. */
    private boolean log(String id, String json) {
        boolean accepted = logger.log(id, json);
        if (mode == Mode.ASYNC) {
            AsyncLogger async = (AsyncLogger) logger;
            for (int i = 0; i < async.workerCount(); i++) {
                while (async.worker(i).runOnce() > 0) {
                    // until its queue is empty
                }
            }
        }
        return accepted;
    }

    private void close() {
        closed = true;
        if (mode == Mode.SYNC) {
            logger.close();
            return;
        }
        AsyncLogger async = (AsyncLogger) logger;
        async.close(Duration.ZERO);
        while (!async.worker(0).closedAndDrained()) {
            for (int i = 0; i < async.workerCount(); i++) {
                async.worker(i).runOnce();
            }
        }
        for (int i = 0; i < async.workerCount(); i++) {
            async.worker(i).finish();
        }
    }

    private void failOpen(String id) {
        sinks.failNextOpen(fakeDir.resolve(id + ".json"), new IOException("denied"));
    }

    private void assertStats(long accepted, long written, long lostIoError) {
        LoggerStats s = logger.stats();
        assertEquals(accepted, s.accepted(), s::toString);
        assertEquals(written, s.written(), s::toString);
        assertEquals(lostIoError, s.lostIoError(), s::toString);
        assertEquals(s.accepted(), s.written() + s.lostIoError() + s.droppedInvalidId(), s::toString);
        assertEquals(0, s.openFiles(), s::toString);
    }

    private static String json(int n) {
        return "{\"n\":" + n + "}";
    }
}
