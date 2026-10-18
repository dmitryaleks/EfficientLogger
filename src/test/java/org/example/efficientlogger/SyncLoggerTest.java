package org.example.efficientlogger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyncLoggerTest {

    private static final long MS = 1_000_000L;

    @TempDir
    Path dir;

    private final List<String> warnings = Collections.synchronizedList(new ArrayList<>());

    private EfficientLoggerBuilder builder() {
        return EfficientLogger.builder(dir, "events-{id}.json")
                .synchronous()
                .warningHandler((message, cause) -> warnings.add(cause == null ? message : message + " | " + cause.getMessage()));
    }

    private Path file(String id) {
        return dir.resolve("events-" + id + ".json");
    }

    private String read(String id) throws IOException {
        return Files.readString(file(id), UTF_8);
    }

    // ---- basics ----

    @Test
    void writesEachMessageBeforeLogReturns() throws IOException {
        try (EfficientLogger logger = builder().build()) {
            assertTrue(logger.log("a", "{\"n\":1}"));
            assertEquals("{\"n\":1}\n", read("a"));

            assertTrue(logger.log("b", "{\"n\":2}"));
            assertTrue(logger.log("a", "{\"n\":3}"));
            assertEquals("{\"n\":1}\n{\"n\":3}\n", read("a"));
            assertEquals("{\"n\":2}\n", read("b"));
        }
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void appendsAcrossRestarts() throws IOException {
        try (EfficientLogger logger = builder().build()) {
            logger.log("a", "{\"run\":1}");
        }
        try (EfficientLogger logger = builder().build()) {
            logger.log("a", "{\"run\":2}");
        }
        assertEquals("{\"run\":1}\n{\"run\":2}\n", read("a"));
    }

    @Test
    void finishesATornLastLineBeforeAppending() throws IOException {
        Files.writeString(file("a"), "{\"cut off", UTF_8);
        try (EfficientLogger logger = builder().build()) {
            logger.log("a", "{}");
        }
        assertEquals("{\"cut off\n{}\n", read("a"));
    }

    @Test
    void usesTheConfiguredLineSeparator() throws IOException {
        try (EfficientLogger logger = builder().lineSeparator("\r\n").build()) {
            logger.log("a", "{\"n\":1}");
            logger.log("a", "{\"n\":2}");
        }
        assertEquals("{\"n\":1}\r\n{\"n\":2}\r\n", read("a"));
    }

    @Test
    void writesMessagesLargerThanTheBufferExactly() throws IOException {
        String big = "{\"text\":\"" + "ab\u00E9\u6771\uD83D\uDE00".repeat(300) + "\"}";
        try (EfficientLogger logger = builder().fileBufferBytes(64).build()) {
            assertTrue(logger.log("a", big));
            assertTrue(logger.log("a", "{}"));
        }
        assertArrayEquals((big + "\n{}\n").getBytes(UTF_8), Files.readAllBytes(file("a")));
    }

    @Test
    void worksWithASingleStripe() throws IOException {
        try (EfficientLogger logger = builder().syncStripes(1).build()) {
            for (int i = 0; i < 10; i++) {
                assertTrue(logger.log("id" + i, "{}"));
            }
        }
        for (int i = 0; i < 10; i++) {
            assertEquals("{}\n", read("id" + i));
        }
    }

    // ---- the directory ----

    @Test
    void createsTheDirectory() throws IOException {
        Path nested = dir.resolve("x").resolve("y");
        try (EfficientLogger logger = EfficientLogger.builder(nested, "{id}.json").synchronous().build()) {
            logger.log("a", "{}");
        }
        assertEquals("{}\n", Files.readString(nested.resolve("a.json"), UTF_8));
    }

    @Test
    void buildFailsWhenTheDirectoryIsAFile() throws IOException {
        Path notADirectory = Files.createFile(dir.resolve("plain-file"));
        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> EfficientLogger.builder(notADirectory, "{id}.json").synchronous().build());
        assertTrue(e.getMessage().startsWith("can't create directory"), e.getMessage());
    }

    @Test
    void asyncModeIsNotAvailableYet() {
        assertThrows(UnsupportedOperationException.class, () -> EfficientLogger.builder(dir, "{id}.json").build());
    }

    // ---- drops and closing ----

    @Test
    void dropsInvalidIdsWithOneWarningEach() throws IOException {
        try (EfficientLogger logger = builder().build()) {
            assertFalse(logger.log("../escape", "{}"));
            assertFalse(logger.log("../escape", "{}"));
            assertEquals(2, logger.stats().droppedInvalidId());
        }
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(0, files.count(), "no file was created");
        }
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("invalid id \"../escape\""), warnings.get(0));
    }

    @Test
    void rejectsMessagesAfterClose() {
        EfficientLogger logger = builder().build();
        assertTrue(logger.log("a", "{}"));
        logger.close();

        assertFalse(logger.log("a", "{}"));
        assertEquals(1, logger.stats().rejectedClosed());
        assertEquals(0, logger.stats().openFiles());
        logger.close(); // again: no effect
        assertTrue(logger.flush());
    }

    @Test
    void statsAddUp() {
        Path fakeDir = dir.toAbsolutePath().normalize();
        FakeSinks sinks = new FakeSinks(fakeDir);
        TestHooks hooks = new TestHooks();
        hooks.sinks = sinks;
        EfficientLogger logger = builder().build(hooks);

        logger.log("a", "{}");
        logger.log("b", "{}");
        logger.log("a/b", "{}");
        sinks.failNextWrite(fakeDir.resolve("events-a.json"), new IOException("disk full"));
        logger.log("a", "{}");
        logger.close();
        logger.log("a", "{}");

        LoggerStats s = logger.stats();
        assertEquals(4, s.accepted());
        assertEquals(2, s.written());
        assertEquals(1, s.droppedInvalidId());
        assertEquals(1, s.lostIoError());
        assertEquals(1, s.rejectedClosed());
        assertEquals(s.accepted(), s.written() + s.droppedInvalidId() + s.lostIoError());
        assertEquals(0, logger.droppedCount());
    }

    @Test
    void anIoErrorDropsMessagesUntilTheRetry() {
        Path fakeDir = dir.toAbsolutePath().normalize();
        FakeSinks sinks = new FakeSinks(fakeDir);
        long[] now = {0};
        TestHooks hooks = new TestHooks();
        hooks.sinks = sinks;
        hooks.nanoClock = () -> now[0];
        Path a = fakeDir.resolve("events-a.json");

        try (EfficientLogger logger = builder().build(hooks)) {
            assertTrue(logger.log("a", "{\"n\":1}"));
            sinks.failNextWrite(a, new IOException("disk full"));
            assertFalse(logger.log("a", "{\"n\":2}"));
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.get(0).endsWith("| disk full"), warnings.get(0));

            now[0] = 999 * MS;
            assertFalse(logger.log("a", "{\"n\":3}"), "still backing off");
            now[0] = 1000 * MS;
            assertTrue(logger.log("a", "{\"n\":4}"));
        }
        assertEquals("{\"n\":1}\n{\"n\":4}\n", sinks.content(a));
    }

    // ---- threads ----

    @Test
    void concurrentCallersKeepLinesWholeAndInOrder() throws Exception {
        int threads = 8;
        int perThread = 10_000;
        int ids = 50;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try (EfficientLogger logger = builder().build()) {
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int thread = t;
                done.add(pool.submit(() -> {
                    start.await();
                    for (int s = 0; s < perThread; s++) {
                        String pad = "x".repeat(s % 40); // vary the length
                        assertTrue(logger.log("id" + (s % ids), "{\"t\":" + thread + ",\"s\":" + s + ",\"pad\":\"" + pad + "\"}"));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : done) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        Pattern line = Pattern.compile("\\{\"t\":(\\d+),\"s\":(\\d+),\"pad\":\"x*\"}");
        int total = 0;
        for (int i = 0; i < ids; i++) {
            Map<Integer, Integer> lastSeq = new HashMap<>();
            for (String l : read("id" + i).split("\n", -1)) {
                if (l.isEmpty()) {
                    continue; // after the final newline
                }
                Matcher m = line.matcher(l);
                assertTrue(m.matches(), "torn or interleaved line: " + l);
                int t = Integer.parseInt(m.group(1));
                int s = Integer.parseInt(m.group(2));
                Integer previous = lastSeq.put(t, s);
                assertTrue(previous == null || previous < s, "out of order for thread " + t + ": " + previous + " then " + s);
                total++;
            }
        }
        assertEquals(threads * perThread, total);
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void aWriteInProgressDoesNotBlockOtherStripes() throws Exception {
        String slowId = "slow";
        String fastId = null;
        for (int i = 0; fastId == null; i++) {
            if (SyncLogger.stripeIndex(("fast" + i).hashCode(), 2) != SyncLogger.stripeIndex(slowId.hashCode(), 2)) {
                fastId = "fast" + i;
            }
        }
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Path slowFile = dir.toAbsolutePath().normalize().resolve("events-slow.json");
        TestHooks hooks = new TestHooks();
        hooks.sinks = new BlockingSinks(slowFile, writing, release);

        Thread slowWriter;
        try (EfficientLogger logger = builder().syncStripes(2).build(hooks)) {
            slowWriter = new Thread(() -> logger.log(slowId, "{}"));
            slowWriter.start();
            assertTrue(writing.await(10, TimeUnit.SECONDS));

            assertTrue(logger.log(fastId, "{}"), "a different stripe isn't blocked");

            release.countDown();
            slowWriter.join(10_000);
        }
        assertEquals("{}\n", read("slow"));
        assertEquals("{}\n", read(fastId));
    }

    @Test
    void stripesAreEvenlyUsed() {
        int[] counts = new int[16];
        for (int i = 0; i < 500; i++) {
            counts[SyncLogger.stripeIndex(("id-" + i).hashCode(), 16)]++;
        }
        for (int c : counts) {
            assertTrue(c >= 16 && c <= 47, "uneven stripes: " + java.util.Arrays.toString(counts)); // mean 31
        }
        assertEquals(0, SyncLogger.stripeIndex("anything".hashCode(), 1));
    }

    @Test
    void anInterruptedCallerStillWritesAndStaysInterrupted() throws IOException {
        try (EfficientLogger logger = builder().build()) {
            Thread.currentThread().interrupt();
            try {
                assertTrue(logger.log("a", "{}"));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted(); // clear it for the rest of the test
            }
            assertTrue(logger.log("a", "{}"));
        }
        assertEquals("{}\n{}\n", read("a"));
    }

    // ---- warning handlers that call back ----

    @Test
    void aWarningHandlerMayLog() throws IOException {
        AtomicReference<EfficientLogger> ref = new AtomicReference<>();
        EfficientLogger logger = builder()
                .warningHandler((message, cause) -> ref.get().log("warnings", "{\"warning\":\"seen\"}"))
                .build();
        ref.set(logger);
        try (logger) {
            assertFalse(logger.log("bad/id", "{}"));
            assertTrue(logger.log("a", "{}"));
        }
        assertEquals("{\"warning\":\"seen\"}\n", read("warnings"));
        assertEquals("{}\n", read("a"));
    }

    @Test
    void aWarningHandlerMayClose() {
        AtomicReference<EfficientLogger> ref = new AtomicReference<>();
        EfficientLogger logger = builder().warningHandler((message, cause) -> ref.get().close()).build();
        ref.set(logger);

        assertFalse(logger.log("bad/id", "{}"));
        assertFalse(logger.log("a", "{}"), "closed by the handler");
        assertEquals(1, logger.stats().rejectedClosed());
    }

    /** Real files, except that the first write to one file blocks until released. */
    private static final class BlockingSinks implements SinkFactory {
        private final Path blockedFile;
        private final CountDownLatch writing;
        private final CountDownLatch release;

        BlockingSinks(Path blockedFile, CountDownLatch writing, CountDownLatch release) {
            this.blockedFile = blockedFile;
            this.writing = writing;
            this.release = release;
        }

        @Override
        public Sink open(Path path) throws IOException {
            Sink real = FileOutputStreamSink.FACTORY.open(path);
            if (!path.equals(blockedFile)) {
                return real;
            }
            return new Sink() {
                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    writing.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new IOException("test timed out");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException(e);
                    }
                    real.write(b, off, len);
                }

                @Override
                public void close() throws IOException {
                    real.close();
                }
            };
        }

        @Override
        public int lastByte(Path path) throws IOException {
            return FileOutputStreamSink.FACTORY.lastByte(path);
        }

        @Override
        public boolean createDirectoryIfMissing(Path dir) throws IOException {
            return FileOutputStreamSink.FACTORY.createDirectoryIfMissing(dir);
        }
    }
}
