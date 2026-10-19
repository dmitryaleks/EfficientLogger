package org.example.efficientlogger;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

/**
 * A soak run rather than a microbenchmark: many loggers, each with one worker, each writing a slow
 * trickle of traces to its own file for a fixed time, through the public API only. By default 100
 * loggers at 5 traces a second each for one minute, on the shared pool, so 100 loggers share four
 * threads. At the end it closes every logger and prints each one's {@link LoggerStats}, the totals,
 * the lines actually on disk, and the latency of the {@code log()} calls.
 *
 * <pre>
 *   .\gradlew.bat manyLoggers
 *   .\gradlew.bat manyLoggers -Pmany.loggers=100 -Pmany.rate=5 -Pmany.seconds=60 -Pmany.producers=4 -Pmany.dir=build/many-loggers
 * </pre>
 *
 * <p>The producers are a few threads that each own a share of the loggers and pace their calls:
 * logger {@code i}'s {@code n}-th trace is due at {@code start + n / rate + i * (1 / rate) / loggers},
 * so the calls are spread evenly over time rather than in bursts. Each call's duration is recorded.
 * The traces are built per call on the producer thread, so the producers allocate; the loggers don't.
 */
public final class ManyLoggersBenchmark {

    private ManyLoggersBenchmark() {}

    public static void main(String[] args) throws Exception {
        int loggers = Integer.getInteger("many.loggers", 100);
        double rate = Double.parseDouble(System.getProperty("many.rate", "5"));
        long seconds = Long.getLong("many.seconds", 60);
        int producers = Integer.getInteger("many.producers", Math.min(4, loggers));
        Path dir = Paths.get(System.getProperty("many.dir", "build/many-loggers")).toAbsolutePath().normalize();
        PrintStream out = System.out;

        clean(dir);
        List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        WarningHandler onWarning = (message, cause) -> {
            warnings.add(cause == null ? message : message + " | " + cause);
            WarningHandler.stderr().warn(message, cause);
        };

        long periodNanos = (long) (TimeUnit.SECONDS.toNanos(1) / rate);
        int tracesPerLogger = (int) (seconds * rate);
        out.printf(Locale.ROOT, "%d loggers, 1 worker each, %s traces/s each (%s/s in all), %d s, %d producer threads, files in %s%n",
                loggers, fmt(rate), fmt(rate * loggers), seconds, producers, dir);

        EfficientLogger[] all = new EfficientLogger[loggers];
        String[] ids = new String[loggers];
        for (int i = 0; i < loggers; i++) {
            ids[i] = String.format(Locale.ROOT, "%03d", i);
            all[i] = EfficientLogger.builder(dir, "logger-{id}.json").warningHandler(onWarning).build();
        }
        out.printf(Locale.ROOT, "built; the shared pool runs %d thread(s)%n", WorkerPool.shared().threads());

        // Each producer owns the loggers i with i % producers == p, and paces them against a common start.
        long start = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200); // let everything settle first
        Producer[] threads = new Producer[producers];
        for (int p = 0; p < producers; p++) {
            threads[p] = new Producer(p, producers, all, ids, start, periodNanos, tracesPerLogger);
            threads[p].start();
        }
        for (Producer t : threads) {
            t.join();
        }
        long produced = System.nanoTime() - start;
        int poolThreads = WorkerPool.shared().threads();
        long gcCount = 0;
        long gcMillis = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += Math.max(0, gc.getCollectionCount());
            gcMillis += Math.max(0, gc.getCollectionTime());
        }

        long closeStart = System.nanoTime();
        int closedCleanly = 0;
        for (EfficientLogger logger : all) {
            if (logger.close(Duration.ofSeconds(10))) {
                closedCleanly++;
            }
        }
        long closeNanos = System.nanoTime() - closeStart;

        // ---- report ----
        out.println();
        out.printf(Locale.ROOT, "%-6s %8s %8s %9s %8s %9s %9s %9s %8s %6s %8s%n",
                "logger", "accepted", "written", "queueFull", "tooLarge", "invalidId", "ioError", "abandoned", "rejClsd", "queued", "lines");
        long accepted = 0, written = 0, queueFull = 0, tooLarge = 0, invalidId = 0, ioError = 0, abandoned = 0, rejected = 0, queued = 0, lines = 0;
        for (int i = 0; i < loggers; i++) {
            LoggerStats s = all[i].stats();
            long n = countLines(dir.resolve("logger-" + ids[i] + ".json"));
            out.printf(Locale.ROOT, "%-6s %8d %8d %9d %8d %9d %9d %9d %8d %6d %8d%n", ids[i], s.accepted(), s.written(),
                    s.droppedQueueFull(), s.droppedTooLarge(), s.droppedInvalidId(), s.lostIoError(), s.abandoned(),
                    s.rejectedClosed(), s.queuedMessages(), n);
            accepted += s.accepted();
            written += s.written();
            queueFull += s.droppedQueueFull();
            tooLarge += s.droppedTooLarge();
            invalidId += s.droppedInvalidId();
            ioError += s.lostIoError();
            abandoned += s.abandoned();
            rejected += s.rejectedClosed();
            queued += s.queuedMessages();
            lines += n;
        }
        out.printf(Locale.ROOT, "%-6s %8d %8d %9d %8d %9d %9d %9d %8d %6d %8d%n", "total", accepted, written, queueFull,
                tooLarge, invalidId, ioError, abandoned, rejected, queued, lines);
        out.println();

        long expected = (long) loggers * tracesPerLogger;
        long attempted = 0;
        long[] latencies = new long[(int) expected];
        long[] lateness = new long[(int) expected];
        int k = 0;
        for (Producer t : threads) {
            attempted += t.calls;
            System.arraycopy(t.latencyNanos, 0, latencies, k, t.calls);
            System.arraycopy(t.latenessNanos, 0, lateness, k, t.calls);
            k += t.calls;
        }
        Arrays.sort(latencies, 0, k);
        Arrays.sort(lateness, 0, k);
        out.printf(Locale.ROOT, "traces: %d attempted of %d planned, %d accepted, %d written, %d lines on disk in %d files; %s%n",
                attempted, expected, accepted, written, lines, countFiles(dir),
                lines == expected && written == expected && accepted == expected ? "nothing lost" : "MISMATCH");
        out.printf(Locale.ROOT, "producers ran %.1f s; pool threads while running: %d; close() of %d loggers took %d ms, %d returned true%n",
                produced / 1e9, poolThreads, loggers, closeNanos / 1_000_000, closedCleanly);
        out.printf(Locale.ROOT, "log() latency (us): p50 %.1f  p99 %.1f  p99.9 %.1f  max %.1f%n",
                pct(latencies, k, 0.50), pct(latencies, k, 0.99), pct(latencies, k, 0.999), latencies[k - 1] / 1e3);
        out.printf(Locale.ROOT, "pacing lateness (us; how long after its due time each call was made): p50 %.1f  p99 %.1f  max %.1f%n",
                pct(lateness, k, 0.50), pct(lateness, k, 0.99), lateness[k - 1] / 1e3);
        out.printf(Locale.ROOT, "GC during the run: %d collections, %d ms in all%n", gcCount, gcMillis);
        out.println("slowest log() calls (JVM uptime in ms, so they can be matched against -Xlog:safepoint,gc output):");
        List<Producer.Slow> slow = new ArrayList<>();
        for (Producer t : threads) {
            slow.addAll(t.slowest);
        }
        slow.sort((a, b) -> Long.compare(b.nanos, a.nanos));
        for (Producer.Slow c : slow.subList(0, Math.min(8, slow.size()))) {
            out.printf(Locale.ROOT, "  %8.1f us at uptime %7d ms: %s logger %s seq %d%s%n",
                    c.nanos / 1e3, c.uptimeMillis, c.producer, c.id, c.seq, c.seq == 0 ? " (the logger's first call)" : "");
        }
        if (warnings.isEmpty()) {
            out.println("warnings: none");
        } else {
            out.println("warnings: " + warnings.size());
            for (String w : warnings) {
                out.println("  " + w);
            }
        }
    }

    /** One producer thread: paces its share of the loggers and records each call's duration and lateness. */
    private static final class Producer extends Thread {
        private final int first;
        private final int step;
        private final EfficientLogger[] loggers;
        private final String[] ids;
        private final long start;
        private final long periodNanos;
        private final int traces;
        final long[] latencyNanos;
        final long[] latenessNanos;
        final List<Slow> slowest = new ArrayList<>(); // the producer's SLOWEST slowest calls
        int calls;

        private static final int SLOWEST = 8;

        /** One slow call, with when it happened. */
        record Slow(long nanos, long uptimeMillis, String producer, String id, int seq) {}

        Producer(int first, int step, EfficientLogger[] loggers, String[] ids, long start, long periodNanos, int traces) {
            super("producer-" + first);
            this.first = first;
            this.step = step;
            this.loggers = loggers;
            this.ids = ids;
            this.start = start;
            this.periodNanos = periodNanos;
            this.traces = traces;
            int mine = (loggers.length - first + step - 1) / step;
            this.latencyNanos = new long[mine * traces];
            this.latenessNanos = new long[mine * traces];
        }

        @Override
        public void run() {
            long stagger = periodNanos / loggers.length; // spreads the loggers' calls over each period
            StringBuilder json = new StringBuilder(96);
            for (int n = 0; n < traces; n++) {
                for (int i = first; i < loggers.length; i += step) {
                    long due = start + n * periodNanos + i * stagger;
                    long now;
                    while ((now = System.nanoTime()) - due < 0) {
                        LockSupport.parkNanos(due - now);
                    }
                    json.setLength(0);
                    json.append("{\"ts\":").append(System.currentTimeMillis())
                            .append(",\"logger\":\"").append(ids[i])
                            .append("\",\"seq\":").append(n)
                            .append(",\"event\":\"trace\",\"thread\":\"").append(getName()).append("\"}");
                    long before = System.nanoTime();
                    loggers[i].log(ids[i], json.toString());
                    long after = System.nanoTime();
                    latencyNanos[calls] = after - before;
                    latenessNanos[calls] = before - due;
                    calls++;
                    noteIfSlow(after - before, before, ids[i], n);
                }
            }
        }

        private void noteIfSlow(long nanos, long atNanoTime, String id, int seq) {
            if (slowest.size() == SLOWEST && slowest.get(SLOWEST - 1).nanos >= nanos) {
                return;
            }
            slowest.add(new Slow(nanos, UPTIME_AT_NANOTIME_ZERO + atNanoTime / 1_000_000, getName(), id, seq));
            slowest.sort((a, b) -> Long.compare(b.nanos, a.nanos));
            if (slowest.size() > SLOWEST) {
                slowest.remove(SLOWEST);
            }
        }
    }

    /** The p-th percentile of the first n sorted values, in microseconds. */
    /** JVM uptime at a nanoTime reading, for matching slow calls against the JVM's own logs. */
    private static final long UPTIME_AT_NANOTIME_ZERO =
            ManagementFactory.getRuntimeMXBean().getUptime() - System.nanoTime() / 1_000_000;

    private static double pct(long[] sorted, int n, double p) {
        int index = (int) Math.ceil(p * n) - 1;
        return sorted[Math.max(0, Math.min(n - 1, index))] / 1e3;
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
    }

    private static void clean(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    if (Files.isRegularFile(f) && f.getFileName().toString().startsWith("logger-")) {
                        Files.delete(f);
                    }
                }
            }
        }
    }

    private static long countLines(Path file) throws IOException {
        if (!Files.exists(file)) {
            return 0;
        }
        long n = 0;
        for (byte b : Files.readAllBytes(file)) {
            if (b == '\n') {
                n++;
            }
        }
        return n;
    }

    private static long countFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().startsWith("logger-")).count();
        }
    }
}
