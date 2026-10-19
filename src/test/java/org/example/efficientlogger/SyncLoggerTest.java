package org.example.efficientlogger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
        return EfficientLogger.builder(dir, "events-{id}.json", 1 << 20)
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

    /** In sync mode there is no worker, so the caller takes the free-space reading when one is due. */
    @Test
    void shedsForLowDiskSpaceAndCapsTheRateInSyncModeToo() throws IOException {
        long[] now = {0};
        long[] free = {10L << 20};
        TestHooks hooks = new TestHooks();
        hooks.nanoClock = () -> now[0];
        hooks.freeSpace = () -> free[0];
        try (EfficientLogger logger = builder().minFreeDiskSpace(1 << 20, 2 << 20).maxCharsPerSecond(20).build(hooks)) {
            assertTrue(logger.log("a", "{\"n\":1}")); // 7 chars: 0.35 s of output each
            assertTrue(logger.log("a", "{\"n\":2}"));
            assertTrue(logger.log("a", "{\"n\":3}"));
            assertFalse(logger.log("a", "{\"n\":4}"), "over the rate");

            free[0] = 100 << 10;
            now[0] = 2_000 * MS; // the rate has recovered, and a free-space reading is due: this call takes it
            assertFalse(logger.log("a", "{\"n\":5}"), "shed");
            LoggerStats s = logger.stats();
            assertEquals(1, s.droppedRateLimit());
            assertEquals(1, s.droppedDiskSpace());
            assertEquals(2, logger.droppedCount());
            assertEquals(3, s.accepted());

            free[0] = 5L << 20;
            now[0] = 3_000 * MS;
            assertTrue(logger.log("a", "{\"n\":6}"));
            assertEquals("{\"n\":1}\n{\"n\":2}\n{\"n\":3}\n{\"n\":6}\n", read("a"));
        }
        assertTrue(warnings.contains("output rate limit reached (maxCharsPerSecond=20); dropping messages until the rate falls"), warnings::toString);
        assertTrue(warnings.contains("free disk space is 100.0 KiB, below minFreeDiskSpace=1.0 MiB; dropping messages until it is back above 2.0 MiB"),
                warnings::toString);
    }

    @Test
    void writesEachMessageBeforeLogReturns() throws IOException {
        try (EfficientLogger logger = builder().build(new TestHooks())) {
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
        try (EfficientLogger logger = builder().build(new TestHooks())) {
            logger.log("a", "{\"run\":1}");
        }
        try (EfficientLogger logger = builder().build(new TestHooks())) {
            logger.log("a", "{\"run\":2}");
        }
        assertEquals("{\"run\":1}\n{\"run\":2}\n", read("a"));
    }

    @Test
    void finishesATornLastLineBeforeAppending() throws IOException {
        Files.writeString(file("a"), "{\"cut off", UTF_8);
        try (EfficientLogger logger = builder().build(new TestHooks())) {
            logger.log("a", "{}");
        }
        assertEquals("{\"cut off\n{}\n", read("a"));
    }

    @Test
    void usesTheConfiguredLineSeparator() throws IOException {
        try (EfficientLogger logger = builder().lineSeparator("\r\n").build(new TestHooks())) {
            logger.log("a", "{\"n\":1}");
            logger.log("a", "{\"n\":2}");
        }
        assertEquals("{\"n\":1}\r\n{\"n\":2}\r\n", read("a"));
    }

    @Test
    void writesMessagesLargerThanTheBufferExactly() throws IOException {
        String big = "{\"text\":\"" + "ab\u00E9\u6771\uD83D\uDE00".repeat(300) + "\"}";
        try (EfficientLogger logger = builder().fileBufferBytes(64).build(new TestHooks())) {
            assertTrue(logger.log("a", big));
            assertTrue(logger.log("a", "{}"));
        }
        assertArrayEquals((big + "\n{}\n").getBytes(UTF_8), Files.readAllBytes(file("a")));
    }

    @Test
    void worksWithASingleStripe() throws IOException {
        try (EfficientLogger logger = builder().syncStripes(1).build(new TestHooks())) {
            for (int i = 0; i < 10; i++) {
                assertTrue(logger.log("id" + i, "{}"));
            }
        }
        for (int i = 0; i < 10; i++) {
            assertEquals("{}\n", read("id" + i));
        }
    }

    @Test
    void worksWithTheLargestMaxOpenFiles() throws IOException {
        try (EfficientLogger logger = builder().maxOpenFiles(Integer.MAX_VALUE).build(new TestHooks())) {
            assertTrue(logger.log("a", "{}"));
        }
        assertEquals("{}\n", read("a"));
    }

    // ---- the directory ----

    @Test
    void createsTheDirectory() throws IOException {
        Path nested = dir.resolve("x").resolve("y");
        try (EfficientLogger logger = EfficientLogger.builder(nested, "{id}.json", 1 << 20).synchronous().build(new TestHooks())) {
            logger.log("a", "{}");
        }
        assertEquals("{}\n", Files.readString(nested.resolve("a.json"), UTF_8));
    }

    @Test
    void buildFailsWhenTheDirectoryIsAFile() throws IOException {
        Path notADirectory = Files.createFile(dir.resolve("plain-file"));
        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> EfficientLogger.builder(notADirectory, "{id}.json", 1 << 20).synchronous().build(new TestHooks()));
        assertTrue(e.getMessage().startsWith("can't create directory"), e.getMessage());
    }

    // ---- drops and closing ----

    @Test
    void dropsInvalidIdsWithOneWarningEach() throws IOException {
        try (EfficientLogger logger = builder().build(new TestHooks())) {
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
    void rejectsIdsTooLongForAFileNameAtOnce() throws IOException {
        int longest = 255 - "events-".length() - ".json".length();
        try (EfficientLogger logger = builder().build(new TestHooks())) {
            assertTrue(logger.log("a".repeat(longest), "{}"));
            assertFalse(logger.log("a".repeat(longest + 1), "{}"));
            assertFalse(logger.log("b".repeat(10_000_000), "{}"));

            LoggerStats s = logger.stats();
            assertEquals(3, s.accepted());
            assertEquals(1, s.written());
            assertEquals(2, s.droppedInvalidId());
        }
        assertEquals("{}\n", read("a".repeat(longest)));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).startsWith("dropping messages whose id is longer than " + longest + " chars"),
                warnings.get(0));
    }

    /** As in async mode: an ID that can't normalize to fit is rejected before it is copied (review R6). */
    @Test
    void normalizeIdsRejectsAHugeIdWithoutCopyingIt() {
        com.sun.management.ThreadMXBean mx = AsyncLoggerTest.allocationCounter();
        try (EfficientLogger logger = builder().normalizeIds().build(new TestHooks())) {
            assertFalse(logger.log("a".repeat(10_000), "{}"), "warms up the rejection path");
            String huge = "x".repeat(20_000_000);

            long before = mx.getCurrentThreadAllocatedBytes();
            assertFalse(logger.log(huge, "{}"));
            long allocated = mx.getCurrentThreadAllocatedBytes() - before;

            assertTrue(allocated < 1_000_000, "rejecting the huge id allocated " + allocated + " bytes: it was copied first");
            assertEquals(2, logger.stats().droppedInvalidId());
        }
        assertEquals(1, warnings.size(), warnings.toString());
    }

    @Test
    void aMessageCantSpanLinesOrCarryControlCodes() throws IOException {
        try (EfficientLogger logger = builder().build(new TestHooks())) {
            logger.log("a", "{\"a\":1}\n{\"b\":2}");
            logger.log("a", "{\"t\":\"" + (char) 0x1B + "[2J\"}");
        }
        assertEquals("{\"a\":1} {\"b\":2}\n{\"t\":\"\\u001b[2J\"}\n", read("a"));
    }

    @Test
    void filePermissionsRestrictNewFilesAndTheDirectory() throws IOException {
        assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "POSIX permissions only");
        Path logs = dir.resolve("private");
        try (EfficientLogger logger = EfficientLogger.builder(logs, "{id}.json", 1 << 20)
                .synchronous()
                .filePermissions(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
                .warningHandler((message, cause) -> warnings.add(message))
                .build(new TestHooks())) {
            assertTrue(logger.log("a", "{}"));
        }
        assertEquals("rwx------",
                java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(logs)));
        assertEquals("rw-------",
                java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(logs.resolve("a.json"))));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void maxFilesCapsTheNumberOfFilesCreated() throws IOException {
        try (EfficientLogger logger = builder().maxFiles(2).build(new TestHooks())) {
            assertTrue(logger.log("a", "{}"));
            assertTrue(logger.log("b", "{}"));
            assertFalse(logger.log("c", "{}"), "over maxFiles: no file for c");
            assertFalse(logger.log("c", "{}"), "still capped");
            assertEquals(2, logger.stats().droppedInvalidId());
        }
        assertTrue(Files.exists(file("a")));
        assertTrue(Files.exists(file("b")));
        assertFalse(Files.exists(file("c")));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("maxFiles"), warnings.get(0));
    }

    @Test
    void warnsAtBuildWhenTheDirectoryIsExposedToOtherUsers() {
        FakeSinks sinks = new FakeSinks(dir.toAbsolutePath().normalize());
        sinks.directoryExposedReason = "the log directory path /x is writable by other users; restrict it";
        TestHooks hooks = new TestHooks();
        hooks.sinks = sinks;
        try (EfficientLogger logger = builder().build(hooks)) {
            assertTrue(logger.log("a", "{}"));
        }
        assertTrue(warnings.stream().anyMatch(w -> w.contains("writable by other users")), warnings.toString());
    }

    @Test
    void rejectsMessagesAfterClose() {
        EfficientLogger logger = builder().build(new TestHooks());
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
        try (EfficientLogger logger = builder().build(new TestHooks())) {
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
            if (Hashing.bucket("fast" + i, 0, 2) != Hashing.bucket(slowId, 0, 2)) {
                fastId = "fast" + i;
            }
        }
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Path slowFile = dir.toAbsolutePath().normalize().resolve("events-slow.json");
        TestHooks hooks = new TestHooks();
        hooks.shardKey = 0; // so the stripe prediction above holds
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
    void anInterruptedCallerStillWritesAndStaysInterrupted() throws IOException {
        try (EfficientLogger logger = builder().build(new TestHooks())) {
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
                .build(new TestHooks());
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
        EfficientLogger logger = builder().warningHandler((message, cause) -> ref.get().close()).build(new TestHooks());
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
