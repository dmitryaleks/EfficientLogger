package org.example.efficientlogger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class ConfigTest {

    @TempDir
    Path dir;

    private EfficientLoggerBuilder builder() {
        return new EfficientLoggerBuilder(dir, "events-{id}.json");
    }

    @Test
    void asyncDefaults() {
        Config c = builder().buildConfig();

        assertFalse(c.synchronous);
        assertEquals(dir.toAbsolutePath().normalize(), c.directory);
        assertEquals("events-a.json", c.fileNamePattern.fileName("a"));
        assertEquals(1, c.workerThreads);
        assertEquals(65_536, c.maxQueuedMessages);
        assertEquals(16L << 20, c.maxQueuedChars);
        assertEquals(8192, c.fileBufferBytes);
        assertEquals(1024, c.maxOpenFiles);
        assertEquals(Duration.ofMillis(100).toNanos(), c.flushIntervalNanos);
        assertFalse(c.eagerWakeup);
        assertEquals(Duration.ofSeconds(10), c.closeTimeout);
        assertTrue(c.registerShutdownHook);
        assertNull(c.threadFactory);
        assertNotNull(c.warningHandler);
        assertArrayEquals(new byte[] {'\n'}, c.lineSeparator);

        assertEquals(65_536, c.ringCapacity);
        assertEquals(256, c.batchSize);
        assertEquals(16_384, c.wakeCount);
        assertEquals(4L << 20, c.wakeChars);
        assertEquals(32_768, c.lowWaterCount);
        assertEquals(8L << 20, c.lowWaterChars);
    }

    @Test
    void theFileEntryCapIsMaxOpenFilesPlusRoomForEntriesWithoutAFile() {
        assertEquals(1024 + FileTable.EXTRA_ENTRIES, builder().buildConfig().maxFileEntries);
        assertEquals(Integer.MAX_VALUE, builder().maxOpenFiles(Integer.MAX_VALUE - 1).buildConfig().maxFileEntries,
                "doesn't overflow");
        assertEquals(Integer.MAX_VALUE, builder().maxOpenFiles(Integer.MAX_VALUE).buildConfig().maxFileEntries);
    }

    @Test
    void syncDefaults() {
        Config c = builder().synchronous().buildConfig();

        assertTrue(c.synchronous);
        assertEquals(16, c.syncStripes);
        assertEquals(8192, c.fileBufferBytes);
        assertEquals(1024, c.maxOpenFiles);
    }

    @Test
    void appliesEverySetting() {
        ThreadFactory factory = Thread::new;
        WarningHandler handler = (message, cause) -> {};
        Config c = builder()
                .workerThreads(4)
                .maxQueuedMessages(1000)
                .maxQueuedChars(50_000)
                .fileBufferBytes(4096)
                .maxOpenFiles(600)
                .flushIntervalMillis(250)
                .eagerWakeup(true)
                .closeTimeout(Duration.ofSeconds(3))
                .registerShutdownHook(false)
                .threadFactory(factory)
                .warningHandler(handler)
                .lineSeparator("\r\n")
                .buildConfig();

        assertEquals(4, c.workerThreads);
        assertEquals(1000, c.maxQueuedMessages);
        assertEquals(50_000, c.maxQueuedChars);
        assertEquals(4096, c.fileBufferBytes);
        assertEquals(600, c.maxOpenFiles);
        assertEquals(Duration.ofMillis(250).toNanos(), c.flushIntervalNanos);
        assertTrue(c.eagerWakeup);
        assertEquals(Duration.ofSeconds(3), c.closeTimeout);
        assertFalse(c.registerShutdownHook);
        assertSame(factory, c.threadFactory);
        assertSame(handler, c.warningHandler);
        assertArrayEquals(new byte[] {'\r', '\n'}, c.lineSeparator);
        assertEquals(4, builder().synchronous().syncStripes(4).buildConfig().syncStripes);
    }

    static Stream<Arguments> outOfRange() {
        return Stream.of(
                option("workerThreads(0)", b -> b.workerThreads(0)),
                option("workerThreads(17)", b -> b.workerThreads(17)),
                option("maxQueuedMessages(0)", b -> b.maxQueuedMessages(0)),
                option("maxQueuedMessages(2^23 + 1)", b -> b.maxQueuedMessages((1 << 23) + 1)),
                option("maxQueuedChars(0)", b -> b.maxQueuedChars(0)),
                option("maxQueuedChars(2^38)", b -> b.maxQueuedChars(1L << 38)),
                option("fileBufferBytes(63)", b -> b.fileBufferBytes(63)),
                option("fileBufferBytes(16 MiB + 1)", b -> b.fileBufferBytes((16 << 20) + 1)),
                option("maxOpenFiles(0)", b -> b.maxOpenFiles(0)),
                option("flushIntervalMillis(-1)", b -> b.flushIntervalMillis(-1)),
                option("flushIntervalMillis(60001)", b -> b.flushIntervalMillis(60_001)),
                option("closeTimeout(-1 ms)", b -> b.closeTimeout(Duration.ofMillis(-1))),
                option("lineSeparator(\"\")", b -> b.lineSeparator("")),
                option("lineSeparator(\"\\r\")", b -> b.lineSeparator("\r")),
                option("lineSeparator(\"\\n\\r\")", b -> b.lineSeparator("\n\r")),
                option("syncStripes(0)", b -> b.syncStripes(0)),
                option("syncStripes(3)", b -> b.syncStripes(3)),
                option("syncStripes(512)", b -> b.syncStripes(512)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("outOfRange")
    void setterRejectsOutOfRangeValue(String call, Consumer<EfficientLoggerBuilder> setter) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> setter.accept(builder()));
        String option = call.substring(0, call.indexOf('('));
        assertTrue(e.getMessage().startsWith(option), e.getMessage());
    }

    static Stream<Arguments> boundaryValues() {
        return Stream.of(
                option("workerThreads(1)", b -> b.workerThreads(1)),
                option("workerThreads(16)", b -> b.workerThreads(16)),
                option("maxQueuedMessages(1)", b -> b.maxQueuedMessages(1)),
                option("maxQueuedMessages(2^23)", b -> b.maxQueuedMessages(1 << 23)),
                option("maxQueuedChars(1)", b -> b.maxQueuedChars(1)),
                option("maxQueuedChars(2^38 - 1)", b -> b.maxQueuedChars((1L << 38) - 1)),
                option("fileBufferBytes(64)", b -> b.fileBufferBytes(64)),
                option("fileBufferBytes(16 MiB)", b -> b.fileBufferBytes(16 << 20)),
                option("maxOpenFiles(1)", b -> b.maxOpenFiles(1)),
                option("flushIntervalMillis(0)", b -> b.flushIntervalMillis(0)),
                option("flushIntervalMillis(60000)", b -> b.flushIntervalMillis(60_000)),
                option("closeTimeout(0)", b -> b.closeTimeout(Duration.ZERO)),
                option("syncStripes(1)", b -> b.synchronous().syncStripes(1)),
                option("syncStripes(256)", b -> b.synchronous().syncStripes(256)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("boundaryValues")
    void acceptsBoundaryValue(String call, Consumer<EfficientLoggerBuilder> setter) {
        EfficientLoggerBuilder b = builder();
        setter.accept(b);
        b.buildConfig();
    }

    static Stream<Arguments> asyncOnlyOptions() {
        return Stream.of(
                option("workerThreads", b -> b.workerThreads(2)),
                option("maxQueuedMessages", b -> b.maxQueuedMessages(10)),
                option("maxQueuedChars", b -> b.maxQueuedChars(10)),
                option("flushIntervalMillis", b -> b.flushIntervalMillis(10)),
                option("eagerWakeup", b -> b.eagerWakeup(false)),
                option("registerShutdownHook", b -> b.registerShutdownHook(true)),
                option("threadFactory", b -> b.threadFactory(Thread::new)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("asyncOnlyOptions")
    void synchronousRejectsAsyncOnlyOption(String option, Consumer<EfficientLoggerBuilder> setter) {
        // The order of the calls doesn't matter: the check happens at build time.
        EfficientLoggerBuilder before = builder();
        setter.accept(before);
        before.synchronous();
        EfficientLoggerBuilder after = builder().synchronous();
        setter.accept(after);

        for (EfficientLoggerBuilder b : new EfficientLoggerBuilder[] {before, after}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, b::buildConfig);
            assertTrue(e.getMessage().contains("async-only options: " + option), e.getMessage());
        }
    }

    @Test
    void synchronousListsEveryConflictingOption() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> builder().synchronous().workerThreads(2).eagerWakeup(true).buildConfig());
        assertTrue(e.getMessage().endsWith("workerThreads, eagerWakeup"), e.getMessage());
    }

    @Test
    void syncStripesRequiresSynchronous() {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> builder().syncStripes(4).buildConfig());
        assertTrue(e.getMessage().contains("synchronous()"), e.getMessage());
    }

    @Test
    void sharedOptionsWorkInSyncMode() {
        Config c = builder()
                .synchronous()
                .fileBufferBytes(128)
                .maxOpenFiles(10)
                .closeTimeout(Duration.ofSeconds(1))
                .lineSeparator("\r\n")
                .warningHandler((message, cause) -> {})
                .buildConfig();
        assertEquals(128, c.fileBufferBytes);
        assertEquals(10, c.maxOpenFiles);
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> new EfficientLoggerBuilder(null, "{id}"));
        assertThrows(NullPointerException.class, () -> new EfficientLoggerBuilder(dir, null));
        assertThrows(NullPointerException.class, () -> builder().closeTimeout(null));
        assertThrows(NullPointerException.class, () -> builder().threadFactory(null));
        assertThrows(NullPointerException.class, () -> builder().warningHandler(null));
        assertThrows(NullPointerException.class, () -> builder().lineSeparator(null));
        assertThrows(NullPointerException.class, () -> builder().filePermissions(null));
    }

    @Test
    void filePermissionsDefaultsToNullAndIsCopiedDefensively() {
        assertNull(builder().buildConfig().filePermissions);

        java.util.Set<java.nio.file.attribute.PosixFilePermission> perms =
                new java.util.HashSet<>(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        EfficientLoggerBuilder b = builder().filePermissions(perms);
        perms.clear(); // a later change to the caller's set must not affect the logger
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), b.buildConfig().filePermissions);
    }

    @Test
    void invalidPatternFailsTheBuild() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> new EfficientLoggerBuilder(dir, "sub/{id}.json").buildConfig());
        assertTrue(e.getMessage().contains("contains '/'"), e.getMessage());
    }

    @Test
    void directoryIsMadeAbsoluteAndNormalized() {
        Config c = new EfficientLoggerBuilder(Paths.get("logs", "..", "out"), "{id}").buildConfig();
        assertEquals(Paths.get("out").toAbsolutePath(), c.directory);
    }

    @Test
    void directoryMustBeOnTheDefaultFileSystem() throws IOException {
        try (FileSystem zip = FileSystems.newFileSystem(dir.resolve("logs.zip"), Map.of("create", "true"))) {
            IllegalArgumentException e = assertThrows(
                    IllegalArgumentException.class,
                    () -> new EfficientLoggerBuilder(zip.getPath("/logs"), "{id}").buildConfig());
            assertTrue(e.getMessage().contains("default file system"), e.getMessage());
        }
    }

    @ParameterizedTest
    @CsvSource({"1, 1", "2, 2", "5, 8", "8, 8", "65536, 65536", "65537, 131072", "8388608, 8388608"})
    void ringCapacityIsTheNextPowerOfTwo(int maxQueuedMessages, int expectedCapacity) {
        assertEquals(expectedCapacity, builder().maxQueuedMessages(maxQueuedMessages).buildConfig().ringCapacity);
    }

    @ParameterizedTest
    @CsvSource({"1, 1", "4, 1", "7, 1", "8, 2", "1000, 250", "1024, 256", "65536, 256"})
    void batchSizeIsAQuarterOfTheQueueUpTo256(int maxQueuedMessages, int expectedBatch) {
        assertEquals(expectedBatch, builder().maxQueuedMessages(maxQueuedMessages).buildConfig().batchSize);
    }

    @Test
    void wakeThresholdsAreAQuarterOfTheLimits() {
        Config c = builder().maxQueuedMessages(64).maxQueuedChars(400).buildConfig();
        assertEquals(16, c.wakeCount);
        assertEquals(100, c.wakeChars);
        assertEquals(32, c.lowWaterCount);
        assertEquals(200, c.lowWaterChars);

        Config tiny = builder().maxQueuedMessages(3).maxQueuedChars(1).buildConfig();
        assertEquals(1, tiny.wakeCount);
        assertEquals(1, tiny.wakeChars);
        assertEquals(1, tiny.lowWaterCount);
        assertEquals(0, tiny.lowWaterChars);
    }

    @Test
    void eagerWakeupWakesOnEveryMessage() {
        Config c = builder().maxQueuedMessages(64).eagerWakeup(true).buildConfig();
        assertEquals(1, c.wakeCount);
        assertEquals(1, c.wakeChars);
    }

    @Test
    void zeroFlushIntervalImpliesEagerWakeup() {
        Config c = builder().flushIntervalMillis(0).eagerWakeup(false).buildConfig();
        assertTrue(c.eagerWakeup);
        assertEquals(0, c.flushIntervalNanos);
        assertEquals(1, c.wakeCount);
    }

    private static Arguments option(String name, Consumer<EfficientLoggerBuilder> setter) {
        return arguments(name, setter);
    }
}
