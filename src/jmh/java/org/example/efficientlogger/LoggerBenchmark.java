package org.example.efficientlogger;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.OutputStreamAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.status.StatusData;
import org.apache.logging.log4j.status.StatusListener;
import org.apache.logging.log4j.status.StatusLogger;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Appending JSON lines to one file per ID (50 IDs), compared across loggers.
 *
 * <ul>
 *   <li>{@code throughput1/4/8}: threads calling {@code log} flat out. The score is calls per
 *       second; {@code accepted} and {@code dropped} split it. The async EfficientLogger drops
 *       instead of blocking when its queue is full, so its {@code accepted} rate is what it
 *       sustains; the others block, so they accept everything.
 *   <li>{@code paced1/4}: each call follows about 0.7 µs of other work, so each thread logs
 *       about a million messages a second. The score is the distribution of time per call,
 *       including that work: compare with {@code none}.
 * </ul>
 *
 * <p>With {@code output=null} (the default) every logger writes to the OS null device: the real
 * write calls happen, but no disk is involved, so this measures the loggers rather than the disk.
 * {@code output=disk} writes real files to a temporary directory.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = "-Xmx1g")
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
public class LoggerBenchmark {

    static final int ID_COUNT = 50;
    static final int PACE_TOKENS = 500; // Blackhole.consumeCPU tokens between paced calls

    private static final Path NULL_DEVICE = NullDeviceSinks.NULL_DEVICE;
    private static final String[] IDS = new String[ID_COUNT];
    private static final String[] MESSAGES = new String[64];

    static {
        for (int i = 0; i < ID_COUNT; i++) {
            IDS[i] = "user-" + i;
        }
        for (int i = 0; i < MESSAGES.length; i++) {
            MESSAGES[i] = "{\"ts\":17000000" + (10_000 + i) + ",\"event\":\"page_view\",\"path\":\"/catalog/items/" + i
                    + "\",\"ms\":" + (i * 7 % 100) + ",\"ok\":true}";
        }
    }

    @Param({"efficient-async", "efficient-async-4w", "efficient-sync", "synchronized-writer", "log4j2-async", "none"})
    public String impl;

    @Param({"null"})
    public String output;

    private Target target;
    private Path dir;

    /** What each logger is reduced to here. */
    interface Target {
        boolean log(String id, String json);

        void close() throws IOException;
    }

    @Setup(Level.Trial)
    public void setUp() throws IOException {
        boolean nullDevice = output.equals("null");
        dir = nullDevice ? Paths.get(System.getProperty("java.io.tmpdir"), "efficient-logger-bench-unused")
                : Files.createTempDirectory("efficient-logger-bench");
        switch (impl) {
            case "efficient-async": target = efficient(false, 1, nullDevice); break;
            case "efficient-async-4w": target = efficient(false, 4, nullDevice); break;
            case "efficient-sync": target = efficient(true, 0, nullDevice); break;
            case "synchronized-writer": target = new SynchronizedWriters(dir, nullDevice); break;
            case "log4j2-async": target = new Log4j2Async(dir, nullDevice); break;
            case "none": target = new Target() {
                    @Override
                    public boolean log(String id, String json) {
                        return true;
                    }

                    @Override
                    public void close() {}
                };
                break;
            default: throw new IllegalArgumentException(impl);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        target.close();
        if (output.equals("disk")) {
            try (Stream<Path> files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    /** Where each thread is in the IDs and messages. Each thread starts somewhere different. */
    @State(Scope.Thread)
    public static class Cursor {
        int id = ThreadLocalRandom.current().nextInt(ID_COUNT);
        int message = ThreadLocalRandom.current().nextInt(MESSAGES.length);

        String id() {
            int i = id;
            id = i + 1 == ID_COUNT ? 0 : i + 1;
            return IDS[i];
        }

        String message() {
            int i = message;
            message = (i + 1) & (MESSAGES.length - 1);
            return MESSAGES[i];
        }
    }

    /** Reported as rates next to the score. */
    @State(Scope.Thread)
    @AuxCounters(AuxCounters.Type.OPERATIONS)
    public static class Outcome {
        public long accepted;
        public long dropped;

        @Setup(Level.Iteration)
        public void reset() {
            accepted = 0;
            dropped = 0;
        }
    }

    @Benchmark
    @Threads(1)
    public void throughput1(Cursor c, Outcome o) {
        log(c, o);
    }

    @Benchmark
    @Threads(4)
    public void throughput4(Cursor c, Outcome o) {
        log(c, o);
    }

    @Benchmark
    @Threads(8)
    public void throughput8(Cursor c, Outcome o) {
        log(c, o);
    }

    @Benchmark
    @Threads(1)
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public boolean paced1(Cursor c) {
        return paced(c);
    }

    @Benchmark
    @Threads(4)
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public boolean paced4(Cursor c) {
        return paced(c);
    }

    private void log(Cursor c, Outcome o) {
        if (target.log(c.id(), c.message())) {
            o.accepted++;
        } else {
            o.dropped++;
        }
    }

    private boolean paced(Cursor c) {
        Blackhole.consumeCPU(PACE_TOKENS);
        return target.log(c.id(), c.message());
    }

    // ---- the loggers ----

    private Target efficient(boolean synchronous, int workers, boolean nullDevice) {
        TestHooks hooks = new TestHooks();
        if (nullDevice) {
            hooks.sinks = new NullDeviceSinks();
        }
        EfficientLoggerBuilder b = EfficientLogger.builder(dir, "{id}.json", 1 << 20)
                .warningHandler((message, cause) -> {}); // drops are counted by Outcome instead
        if (synchronous) {
            b.synchronous();
        } else {
            b.workerThreads(workers);
        }
        EfficientLogger logger = b.build(hooks);
        return new Target() {
            @Override
            public boolean log(String id, String json) {
                return logger.log(id, json);
            }

            @Override
            public void close() {
                logger.close();
                LoggerStats s = logger.stats();
                if (s.lostIoError() != 0 || s.droppedInvalidId() != 0) {
                    throw new IllegalStateException("messages were lost, so the results are wrong: " + s);
                }
            }
        };
    }

    /** The straightforward way: a BufferedWriter per file, each locked while a line is written. */
    static final class SynchronizedWriters implements Target {
        private final ConcurrentHashMap<String, Writer> writers = new ConcurrentHashMap<>();
        private final Path dir;
        private final boolean nullDevice;

        SynchronizedWriters(Path dir, boolean nullDevice) {
            this.dir = dir;
            this.nullDevice = nullDevice;
        }

        @Override
        public boolean log(String id, String json) {
            Writer w = writers.get(id);
            if (w == null) {
                w = writers.computeIfAbsent(id, this::open);
            }
            try {
                synchronized (w) {
                    w.write(json);
                    w.write('\n');
                }
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        private Writer open(String id) {
            Path file = nullDevice ? NULL_DEVICE : dir.resolve(id + ".json");
            try {
                return new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file.toFile(), true), UTF_8), 8192);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void close() throws IOException {
            for (Writer w : writers.values()) {
                synchronized (w) {
                    w.close();
                }
            }
        }
    }

    /**
     * log4j2 async loggers (LMAX Disruptor), garbage-free by default: a logger per ID, each with
     * its own appender and buffered stream, like a configuration with one logger per file. When
     * the ring buffer is full, it blocks.
     */
    static final class Log4j2Async implements Target {
        private final HashMap<String, org.apache.logging.log4j.Logger> loggers = new HashMap<>();
        private final List<String> problems = Collections.synchronizedList(new ArrayList<>());
        private final List<Appender> appenders = new ArrayList<>();
        private final List<OutputStream> streams = new ArrayList<>(); // OutputStreamAppender never closes them
        private final LoggerContext context;

        Log4j2Async(Path dir, boolean nullDevice) throws IOException {
            // log4j2 reports its own failures to its status log and carries on; collect them to fail the run.
            StatusLogger.getLogger().registerListener(new StatusListener() {
                @Override
                public void log(StatusData data) {
                    if (data.getLevel().isMoreSpecificThan(org.apache.logging.log4j.Level.WARN)) {
                        problems.add(data.getFormattedStatus());
                    }
                }

                @Override
                public org.apache.logging.log4j.Level getStatusLevel() {
                    return org.apache.logging.log4j.Level.WARN;
                }

                @Override
                public void close() {}
            });
            System.setProperty("log4j2.contextSelector",
                    "org.apache.logging.log4j.core.async.AsyncLoggerContextSelector");
            System.setProperty("log4j2.configurationFile", "log4j2-bench.xml");
            context = (LoggerContext) LogManager.getContext(false);
            Configuration config = context.getConfiguration();
            PatternLayout layout = PatternLayout.newBuilder().withPattern("%m%n").withConfiguration(config).build();
            for (String id : IDS) {
                Path file = nullDevice ? NULL_DEVICE : dir.resolve(id + ".json");
                OutputStream stream = new FileOutputStream(file.toFile(), true);
                streams.add(stream);
                Appender appender = OutputStreamAppender.newBuilder()
                        .setName("file-" + id)
                        .setTarget(stream)
                        .setLayout(layout)
                        .setImmediateFlush(false) // async loggers still flush at the end of each batch
                        .setConfiguration(config)
                        .build();
                appender.start();
                appenders.add(appender);
                config.addAppender(appender);
                LoggerConfig loggerConfig = LoggerConfig.newBuilder()
                        .withLoggerName("bench." + id)
                        .withLevel(org.apache.logging.log4j.Level.INFO)
                        .withAdditivity(false)
                        .withIncludeLocation("false")
                        .withConfig(config)
                        .build();
                loggerConfig.addAppender(appender, null, null);
                config.addLogger("bench." + id, loggerConfig);
            }
            context.updateLoggers();
            for (String id : IDS) {
                loggers.put(id, context.getLogger("bench." + id));
            }
        }

        @Override
        public boolean log(String id, String json) {
            loggers.get(id).info(json);
            return true;
        }

        @Override
        public void close() throws IOException {
            context.stop(); // drains the ring buffer
            for (Appender appender : appenders) {
                appender.stop(); // writes out its buffer
            }
            for (OutputStream stream : streams) {
                stream.close();
            }
            if (!problems.isEmpty()) {
                throw new IllegalStateException("log4j2 reported problems, so the results are wrong: " + problems);
            }
        }
    }
}
