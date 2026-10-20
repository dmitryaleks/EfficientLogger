package org.example.efficientlogger;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

/**
 * An executable test rather than a microbenchmark: many writer threads, one ID each, each logging
 * JSON at a slow steady rate to its own file, all through one logger whose single worker thread
 * does every write. By default 500 writers at 5 lines a second each, 1,000 lines each, so about
 * 200 seconds and 500,000 lines into 500 files through one thread. Through the public API, with
 * real files, on Linux or Windows.
 *
 * <pre>
 *   .\gradlew.bat singleWorkerSoak
 *   .\gradlew.bat singleWorkerSoak -Psoak.writers=500 -Psoak.rate=5 -Psoak.lines=1000 -Psoak.dir=build/single-worker-soak
 *   .\gradlew.bat singleWorkerSoak -Psoak.flushMillis=100 -Psoak.fileKb=1048576 -Psoak.verbose=true
 * </pre>
 *
 * <p>Each writer paces itself: its {@code n}-th line is due at {@code start + n / rate + i * (1 / rate) / writers},
 * so the writers' calls are spread evenly over each period rather than arriving in a burst. A
 * writer records when each {@code log()} call started, how long it took and how late it was, and
 * when its last call returned. A watcher thread waits for the last writer and then polls
 * {@code stats()} until every accepted line has been handed to the OS ({@code written}, which the
 * worker counts only once a file's buffer is flushed): the time from the last {@code log()} return
 * to that moment is the gap between the producers finishing and the worker finishing its I/O. Then
 * {@code flush()} and {@code close()} are timed too, and every file is read back and checked: the
 * right number of lines, each one the JSON that was logged, in sequence.
 *
 * <p>Each line is about 340 bytes: a timestamp, the writer, the sequence number, the thread, and a
 * {@code payload} of {@value #PAYLOAD_CHARS} hex digits of pseudo-random bits, like a run of hashes,
 * different on every line, so the worker encodes and writes something the size of a real record.
 *
 * <p>Prints the timeline, the writers' figures, the {@code log()} latency, the worker's CPU time and
 * queue depth, the final {@link LoggerStats}, what is on disk, GC and warnings, then PASS or FAIL
 * (exit code 1): every line accepted and written, every file complete and in order, and exactly one
 * worker thread seen.
 */
public final class SingleWorkerSoak {

    /** Length of each line's {@code payload} field: hex digits of pseudo-random bits, like a run of hashes. */
    static final int PAYLOAD_CHARS = 256;

    private SingleWorkerSoak() {}

    public static void main(String[] args) throws Exception {
        int writers = Integer.getInteger("soak.writers", 500);
        double rate = Double.parseDouble(System.getProperty("soak.rate", "5"));
        int lines = Integer.getInteger("soak.lines", 1000);
        Path dir = Paths.get(System.getProperty("soak.dir", "build/single-worker-soak")).toAbsolutePath().normalize();
        long fileKb = Long.getLong("soak.fileKb", 1 << 20);
        Long flushMillis = Long.getLong("soak.flushMillis");
        boolean verbose = Boolean.getBoolean("soak.verbose");
        long drainTimeoutSeconds = Long.getLong("soak.drainTimeoutSeconds", 60);
        PrintStream out = System.out;

        clean(dir);
        List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        WarningHandler onWarning = (message, cause) -> {
            warnings.add(cause == null ? message : message + " | " + cause);
            WarningHandler.stderr().warn(message, cause);
        };

        long periodNanos = (long) (TimeUnit.SECONDS.toNanos(1) / rate);
        long expectedTotal = (long) writers * lines;
        out.printf(Locale.ROOT, "SingleWorkerSoak: %d writers, %s lines/s each (%s/s in all), %d lines each (%d in all), 1 logger, 1 worker thread%n",
                writers, fmt(rate), fmt(rate * writers), lines, expectedTotal);
        out.printf(Locale.ROOT, "each line: JSON with ts, writer, seq, event, thread and a %d-hex-digit random payload, about 340 bytes%n", PAYLOAD_CHARS);
        out.printf(Locale.ROOT, "files: %s%swriter-{id}.json, maxFileSizeKb=%d, flushIntervalMillis=%s%n",
                dir, dir.getFileSystem().getSeparator(), fileKb, flushMillis == null ? "100 (default)" : flushMillis.toString());
        out.printf(Locale.ROOT, "host: %s %s, %d processors, Java %s (%s)%n", System.getProperty("os.name"),
                System.getProperty("os.version"), Runtime.getRuntime().availableProcessors(),
                System.getProperty("java.version"), System.getProperty("java.vm.name"));
        out.printf(Locale.ROOT, "expected: each writer runs about %.1f s; the whole run about %.1f s plus the drain and the checks%n",
                (lines - 1) / rate, (lines - 1) / rate + 0.5);
        out.println();

        long t0 = System.nanoTime();
        WorkerPool pool = WorkerPool.create(1);
        EfficientLoggerBuilder builder = EfficientLogger.builder(dir, "writer-{id}.json", fileKb)
                .workerPool(pool)
                .workerThreads(1)
                .warningHandler(onWarning);
        if (flushMillis != null) {
            builder.flushIntervalMillis(flushMillis);
        }
        EfficientLogger logger = builder.build();
        long built = System.nanoTime();

        // The writers start, report ready, and wait for the common start, so the late starters
        // among 500 threads don't begin behind their first due time.
        CountDownLatch ready = new CountDownLatch(writers);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(writers);
        long[] startHolder = new long[1];
        Writer[] all = new Writer[writers];
        ThreadGroup group = new ThreadGroup("writers");
        for (int i = 0; i < writers; i++) {
            all[i] = new Writer(group, i, writers, logger, lines, periodNanos, startHolder, ready, go, finished);
            all[i].start();
        }
        ready.await();
        long threadsUp = System.nanoTime();

        Sampler sampler = new Sampler(logger, pool);
        sampler.start();
        Drainer drainer = new Drainer(logger, finished, drainTimeoutSeconds);
        drainer.start();

        long gcCount0 = gcCount();
        long gcMillis0 = gcMillis();
        long start = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
        startHolder[0] = start;
        go.countDown();

        for (Writer w : all) {
            w.join();
        }
        long joined = System.nanoTime();
        drainer.join(TimeUnit.SECONDS.toMillis(drainTimeoutSeconds) + 5_000);
        long workerCpuNanos = workerCpuNanos(); // while the worker thread is still alive
        int poolThreadsAtEnd = pool.threads();
        LoggerStats drained = logger.stats();

        long flushStart = System.nanoTime();
        boolean flushed = logger.flush(Duration.ofSeconds(30));
        long flushEnd = System.nanoTime();
        boolean closed = logger.close(Duration.ofSeconds(30));
        long closeEnd = System.nanoTime();
        sampler.stop = true;
        sampler.join();
        LoggerStats s = logger.stats();
        long gcCount = gcCount() - gcCount0;
        long gcMillis = gcMillis() - gcMillis0;

        // ---- the writers' figures ----
        long lastReturn = Long.MIN_VALUE;
        long firstReturn = Long.MAX_VALUE;
        long firstCall = Long.MAX_VALUE;
        long attempted = 0;
        long accepted = 0;
        long writerCpuNanos = 0;
        int n = 0;
        long[] latencies = new long[(int) expectedTotal];
        long[] lateness = new long[(int) expectedTotal];
        double[] durations = new double[writers];
        double[] finishOffsets = new double[writers];
        List<Writer.Slow> slow = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            Writer w = all[i];
            attempted += w.calls;
            accepted += w.accepted;
            writerCpuNanos += w.cpuNanos;
            System.arraycopy(w.latencyNanos, 0, latencies, n, w.calls);
            System.arraycopy(w.latenessNanos, 0, lateness, n, w.calls);
            n += w.calls;
            lastReturn = Math.max(lastReturn, w.lastReturnAt);
            firstReturn = Math.min(firstReturn, w.lastReturnAt);
            firstCall = Math.min(firstCall, w.firstCallAt);
            durations[i] = (w.lastReturnAt - w.firstCallAt) / 1e9;
            finishOffsets[i] = (w.lastReturnAt - start) / 1e9;
            slow.addAll(w.slowest);
        }
        Arrays.sort(latencies, 0, n);
        Arrays.sort(lateness, 0, n);
        double[] sortedDurations = durations.clone();
        Arrays.sort(sortedDurations);
        slow.sort((a, b) -> Long.compare(b.nanos, a.nanos));

        // ---- what is on disk ----
        List<String> anomalies = new ArrayList<>();
        long linesOnDisk = 0;
        long bytesOnDisk = 0;
        int minLines = Integer.MAX_VALUE;
        int maxLines = 0;
        int[] linesPerWriter = new int[writers];
        for (int i = 0; i < writers; i++) {
            Path file = dir.resolve("writer-" + all[i].id + ".json");
            FileCheck check = checkFile(file, all[i].id, lines);
            linesPerWriter[i] = check.lines;
            linesOnDisk += check.lines;
            bytesOnDisk += check.bytes;
            minLines = Math.min(minLines, check.lines);
            maxLines = Math.max(maxLines, check.lines);
            if (check.problem != null) {
                anomalies.add("writer " + all[i].id + ": " + check.problem);
            }
            if (all[i].accepted != lines) {
                anomalies.add("writer " + all[i].id + ": accepted " + all[i].accepted + " of " + lines + " log() calls");
            }
        }
        long filesOnDisk = countFiles(dir);

        // ---- report ----
        out.println("timeline (seconds after the common start; the writers' first lines were due at 0):");
        out.printf(Locale.ROOT, "  %-52s %9.3f%n", "logger built (took " + ms(built - t0) + " ms)", sec(built, start));
        out.printf(Locale.ROOT, "  %-52s %9.3f%n", writers + " writer threads started and ready", sec(threadsUp, start));
        out.printf(Locale.ROOT, "  %-52s %9.3f%n", "first log() call", sec(firstCall, start));
        out.printf(Locale.ROOT, "  %-52s %9.3f%n", "first writer's last log() returned", sec(firstReturn, start));
        out.printf(Locale.ROOT, "  %-52s %9.3f   <- producers done%n", "last writer's last log() returned", sec(lastReturn, start));
        out.printf(Locale.ROOT, "  %-52s %9.3f%n", "all writers joined by main", sec(joined, start));
        if (drainer.drainedAt != 0) {
            out.printf(Locale.ROOT, "  %-52s %9.3f   <- worker done: written == accepted%n",
                    "every accepted line handed to the OS", sec(drainer.drainedAt, start));
        } else {
            out.printf(Locale.ROOT, "  %-52s %9s%n", "every accepted line handed to the OS", "NEVER (drain timed out)");
        }
        out.printf(Locale.ROOT, "  %-52s %9.3f%n", "flush() returned " + flushed, sec(flushEnd, start));
        out.printf(Locale.ROOT, "  %-52s %9.3f%n", "close() returned " + closed, sec(closeEnd, start));
        out.println();
        out.println("gaps:");
        if (drainer.drainedAt != 0) {
            out.printf(Locale.ROOT, "  producers done -> worker done with all I/O:   %9.3f ms  (%d stats() polls; %d lines still queued, %d still unwritten when the last log() returned)%n",
                    (drainer.drainedAt - lastReturn) / 1e6, drainer.polls, drainer.firstQueued, drainer.firstUnwritten);
        }
        out.printf(Locale.ROOT, "  producers done -> flush() returned:           %9.3f ms  (flush() itself took %.3f ms)%n",
                (flushEnd - lastReturn) / 1e6, (flushEnd - flushStart) / 1e6);
        out.printf(Locale.ROOT, "  producers done -> close() returned:           %9.3f ms  (close() itself took %.3f ms)%n",
                (closeEnd - lastReturn) / 1e6, (closeEnd - flushEnd) / 1e6);
        out.printf(Locale.ROOT, "  whole run, build to close:                    %9.3f s%n", (closeEnd - t0) / 1e9);
        out.println();

        out.println("writers:");
        out.printf(Locale.ROOT, "  log() calls: %d attempted of %d planned, %d accepted, %d refused at log()%n",
                attempted, expectedTotal, accepted, attempted - accepted);
        out.printf(Locale.ROOT, "  per-writer run time (first call to last return), s: min %.3f  median %.3f  max %.3f  (planned %.3f)%n",
                sortedDurations[0], sortedDurations[writers / 2], sortedDurations[writers - 1], (lines - 1) / rate);
        out.printf(Locale.ROOT, "  finish spread (first writer done to last writer done): %.3f ms  (planned stagger %.3f ms)%n",
                (lastReturn - firstReturn) / 1e6, periodNanos / 1e6 * (writers - 1) / writers);
        out.printf(Locale.ROOT, "  log() latency (us): p50 %.1f  p90 %.1f  p99 %.1f  p99.9 %.1f  max %.1f%n",
                pct(latencies, n, 0.50), pct(latencies, n, 0.90), pct(latencies, n, 0.99), pct(latencies, n, 0.999), latencies[n - 1] / 1e3);
        out.printf(Locale.ROOT, "  pacing lateness (us; how long after its due time each call was made): p50 %.1f  p99 %.1f  max %.1f%n",
                pct(lateness, n, 0.50), pct(lateness, n, 0.99), lateness[n - 1] / 1e3);
        out.printf(Locale.ROOT, "  writer threads' CPU time in all: %.1f ms (%.2f%% of one core over the run)%n",
                writerCpuNanos / 1e6, 100.0 * writerCpuNanos / (lastReturn - start));
        out.println("  slowest log() calls (JVM uptime in ms, to match against -Xlog:safepoint,gc output):");
        for (Writer.Slow c : slow.subList(0, Math.min(8, slow.size()))) {
            out.printf(Locale.ROOT, "    %8.1f us at uptime %7d ms: writer %s seq %d%s%n",
                    c.nanos / 1e3, c.uptimeMillis, c.id, c.seq, c.seq == 0 ? " (the writer's first call)" : "");
        }
        out.println();

        out.println("worker:");
        out.printf(Locale.ROOT, "  pool threads: max %d seen while running (expected 1), %d at the end of the drain%n",
                sampler.maxPoolThreads, poolThreadsAtEnd);
        if (workerCpuNanos >= 0) {
            out.printf(Locale.ROOT, "  worker thread CPU time: %.1f ms (%.2f%% of one core over the run), %.2f us per line written%n",
                    workerCpuNanos / 1e6, 100.0 * workerCpuNanos / (drainer.drainedAt != 0 ? drainer.drainedAt - start : joined - start),
                    drained.written() > 0 ? workerCpuNanos / 1e3 / drained.written() : 0.0);
        } else {
            out.println("  worker thread CPU time: not available on this JVM");
        }
        out.printf(Locale.ROOT, "  queue depth, sampled every %d ms (%d samples): messages max %d, mean %.2f; chars max %d; the queue holds up to %d messages%n",
                Sampler.PERIOD_MILLIS, sampler.samples, sampler.maxQueued, sampler.samples > 0 ? (double) sampler.sumQueued / sampler.samples : 0.0,
                sampler.maxQueuedChars, Config.DEFAULT_MAX_QUEUED_MESSAGES);
        out.printf(Locale.ROOT, "  open files: max %d seen while running (of %d writers), %d after close%n",
                sampler.maxOpenFiles, writers, s.openFiles());
        out.printf(Locale.ROOT, "  written, sampled: %d lines behind the writers at worst (the largest accepted - written seen)%n", sampler.maxBehind);
        out.println();

        out.println("final stats():");
        out.printf(Locale.ROOT, "  accepted %d, written %d, queuedMessages %d, queuedChars %d, openFiles %d%n",
                s.accepted(), s.written(), s.queuedMessages(), s.queuedChars(), s.openFiles());
        out.printf(Locale.ROOT, "  dropped: queueFull %d, tooLarge %d, invalidId %d, fileFull %d, diskSpace %d, rateLimit %d; lostIoError %d, abandoned %d, rejectedClosed %d%n",
                s.droppedQueueFull(), s.droppedTooLarge(), s.droppedInvalidId(), s.droppedFileFull(), s.droppedDiskSpace(),
                s.droppedRateLimit(), s.lostIoError(), s.abandoned(), s.rejectedClosed());
        out.println();

        out.println("on disk:");
        out.printf(Locale.ROOT, "  %d files, %d lines, %d bytes (%.1f bytes per line); lines per file min %d, max %d (expected %d)%n",
                filesOnDisk, linesOnDisk, bytesOnDisk, linesOnDisk > 0 ? (double) bytesOnDisk / linesOnDisk : 0.0, minLines, maxLines, lines);
        out.printf(Locale.ROOT, "  every line checked: JSON object, the right writer, seq 0..%d in order, a %d-hex-digit payload, no stray CR: %s%n",
                lines - 1, PAYLOAD_CHARS, anomalies.isEmpty() ? "yes" : anomalies.size() + " problem(s), listed below");
        out.printf(Locale.ROOT, "  GC during the run: %d collections, %d ms in all%n", gcCount, gcMillis);
        if (warnings.isEmpty()) {
            out.println("  warnings: none");
        } else {
            out.println("  warnings: " + warnings.size());
            for (String w : warnings) {
                out.println("    " + w);
            }
        }
        for (String a : anomalies) {
            out.println("  PROBLEM " + a);
        }
        out.println();

        if (verbose) {
            out.println("per writer:");
            out.printf(Locale.ROOT, "  %-6s %8s %8s %9s %10s %10s %10s%n", "writer", "accepted", "lines", "run(s)", "done at(s)", "p50(us)", "max(us)");
            for (int i = 0; i < writers; i++) {
                Writer w = all[i];
                long[] mine = Arrays.copyOf(w.latencyNanos, w.calls);
                Arrays.sort(mine);
                out.printf(Locale.ROOT, "  %-6s %8d %8d %9.3f %10.3f %10.1f %10.1f%n", w.id, w.accepted, linesPerWriter[i],
                        durations[i], finishOffsets[i], w.calls > 0 ? pct(mine, w.calls, 0.5) : 0.0, w.calls > 0 ? mine[w.calls - 1] / 1e3 : 0.0);
            }
            out.println();
        }

        boolean pass = attempted == expectedTotal && accepted == expectedTotal && s.written() == expectedTotal
                && linesOnDisk == expectedTotal && filesOnDisk == writers && anomalies.isEmpty()
                && drainer.drainedAt != 0 && flushed && closed && sampler.maxPoolThreads == 1;
        out.printf(Locale.ROOT, "%s: %d lines planned, %d accepted, %d written, %d on disk in %d files; worker threads seen %d; drained %s, flushed %s, closed %s%n",
                pass ? "PASS" : "FAIL", expectedTotal, accepted, s.written(), linesOnDisk, filesOnDisk, sampler.maxPoolThreads,
                drainer.drainedAt != 0 ? "yes" : "NO", flushed ? "yes" : "NO", closed ? "yes" : "NO");
        out.flush();
        if (!pass) {
            System.exit(1);
        }
    }

    /** One writer thread: paces its own lines and records each call's duration and lateness. */
    private static final class Writer extends Thread {
        private static final int SLOWEST = 4;
        private static final long STACK_BYTES = 256 * 1024; // 500 threads: keep the stacks small
        private static final char[] HEX = "0123456789abcdef".toCharArray();

        private long payloadState;

        final String id;
        private final int index;
        private final int writers;
        private final EfficientLogger logger;
        private final int lines;
        private final long periodNanos;
        private final long[] startHolder;
        private final CountDownLatch ready;
        private final CountDownLatch go;
        private final CountDownLatch finished;
        final long[] latencyNanos;
        final long[] latenessNanos;
        final List<Slow> slowest = new ArrayList<>();
        int calls;
        int accepted;
        long firstCallAt;
        long lastReturnAt;
        long cpuNanos;

        /** One slow call, with when it happened. */
        record Slow(long nanos, long uptimeMillis, String id, int seq) {}

        Writer(ThreadGroup group, int index, int writers, EfficientLogger logger, int lines, long periodNanos, long[] startHolder,
                CountDownLatch ready, CountDownLatch go, CountDownLatch finished) {
            super(group, null, "writer-" + String.format(Locale.ROOT, "%03d", index), STACK_BYTES);
            this.id = String.format(Locale.ROOT, "%03d", index);
            this.index = index;
            this.writers = writers;
            this.logger = logger;
            this.lines = lines;
            this.periodNanos = periodNanos;
            this.startHolder = startHolder;
            this.ready = ready;
            this.go = go;
            this.finished = finished;
            this.latencyNanos = new long[lines];
            this.latenessNanos = new long[lines];
            this.payloadState = 0x9E3779B97F4A7C15L * (index + 1) ^ System.nanoTime() | 1; // never 0: xorshift would stick there
        }

        @Override
        public void run() {
            try {
                ready.countDown();
                go.await();
                long start = startHolder[0]; // published by the latch
                long offset = periodNanos / writers * index; // spreads the writers over each period
                ThreadMXBean threads = ManagementFactory.getThreadMXBean();
                StringBuilder json = new StringBuilder(128 + PAYLOAD_CHARS);
                for (int seq = 0; seq < lines; seq++) {
                    long due = start + seq * periodNanos + offset;
                    long now;
                    while ((now = System.nanoTime()) - due < 0) {
                        LockSupport.parkNanos(due - now);
                    }
                    json.setLength(0);
                    json.append("{\"ts\":").append(System.currentTimeMillis())
                            .append(",\"writer\":\"").append(id)
                            .append("\",\"seq\":").append(seq)
                            .append(",\"event\":\"trace\",\"thread\":\"").append(getName())
                            .append("\",\"payload\":\"");
                    appendPayload(json);
                    json.append("\"}");
                    long before = System.nanoTime();
                    boolean ok = logger.log(id, json.toString());
                    long after = System.nanoTime();
                    if (seq == 0) {
                        firstCallAt = before;
                    }
                    lastReturnAt = after;
                    latencyNanos[calls] = after - before;
                    latenessNanos[calls] = before - due;
                    calls++;
                    if (ok) {
                        accepted++;
                    }
                    noteIfSlow(after - before, before, seq);
                }
                cpuNanos = threads.isCurrentThreadCpuTimeSupported() ? threads.getCurrentThreadCpuTime() : 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
        }

        /**
         * Appends {@link #PAYLOAD_CHARS} hex digits of pseudo-random bits, like a run of hashes, from
         * a per-writer xorshift state: no allocation, and a different payload on every line.
         */
        private void appendPayload(StringBuilder json) {
            long x = payloadState;
            for (int word = 0; word < PAYLOAD_CHARS / 16; word++) {
                x ^= x << 13;
                x ^= x >>> 7;
                x ^= x << 17;
                for (int shift = 60; shift >= 0; shift -= 4) {
                    json.append(HEX[(int) (x >>> shift) & 0xF]);
                }
            }
            payloadState = x;
        }

        private void noteIfSlow(long nanos, long atNanoTime, int seq) {
            if (slowest.size() == SLOWEST && slowest.get(SLOWEST - 1).nanos >= nanos) {
                return;
            }
            slowest.add(new Slow(nanos, UPTIME_AT_NANOTIME_ZERO + atNanoTime / 1_000_000, id, seq));
            slowest.sort((a, b) -> Long.compare(b.nanos, a.nanos));
            if (slowest.size() > SLOWEST) {
                slowest.remove(SLOWEST);
            }
        }
    }

    /**
     * Waits for the last writer, then polls {@code stats()} until every accepted line is written
     * (or otherwise accounted for) and the queue is empty, noting when that happened.
     */
    private static final class Drainer extends Thread {
        private final EfficientLogger logger;
        private final CountDownLatch finished;
        private final long timeoutSeconds;
        volatile long drainedAt; // nanoTime; 0 if it never drained
        volatile long polls;
        volatile long firstQueued; // queued messages at the first poll, just after the last log() returned
        volatile long firstUnwritten; // accepted - written at the first poll

        Drainer(EfficientLogger logger, CountDownLatch finished, long timeoutSeconds) {
            super("drainer");
            this.logger = logger;
            this.finished = finished;
            this.timeoutSeconds = timeoutSeconds;
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                finished.await();
            } catch (InterruptedException e) {
                return;
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            long count = 0;
            while (true) {
                LoggerStats s = logger.stats();
                long now = System.nanoTime();
                count++;
                long accountedFor = s.written() + s.droppedInvalidId() + s.droppedFileFull() + s.lostIoError() + s.abandoned();
                if (count == 1) {
                    firstQueued = s.queuedMessages();
                    firstUnwritten = s.accepted() - s.written();
                }
                if (accountedFor >= s.accepted() && s.queuedMessages() == 0) {
                    drainedAt = now;
                    polls = count;
                    return;
                }
                if (now - deadline > 0) {
                    polls = count;
                    return;
                }
                LockSupport.parkNanos(20_000); // 20 us between polls: fine enough, and stats() allocates
            }
        }
    }

    /** Samples the queue, the open files and the pool's thread count while the run is on. */
    private static final class Sampler extends Thread {
        static final int PERIOD_MILLIS = 50;

        private final EfficientLogger logger;
        private final WorkerPool pool;
        volatile boolean stop;
        long samples;
        long maxQueued;
        long sumQueued;
        long maxQueuedChars;
        int maxOpenFiles;
        int maxPoolThreads;
        long maxBehind;

        Sampler(EfficientLogger logger, WorkerPool pool) {
            super("sampler");
            this.logger = logger;
            this.pool = pool;
            setDaemon(true);
        }

        @Override
        public void run() {
            while (!stop) {
                LoggerStats s = logger.stats();
                samples++;
                maxQueued = Math.max(maxQueued, s.queuedMessages());
                sumQueued += s.queuedMessages();
                maxQueuedChars = Math.max(maxQueuedChars, s.queuedChars());
                maxOpenFiles = Math.max(maxOpenFiles, s.openFiles());
                maxPoolThreads = Math.max(maxPoolThreads, pool.threads());
                maxBehind = Math.max(maxBehind, s.accepted() - s.written());
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(PERIOD_MILLIS));
            }
        }
    }

    /** What a read-back of one writer's file found. */
    private record FileCheck(int lines, long bytes, String problem) {}

    /**
     * Reads a file back and checks it: {@code expectedLines} lines, each a JSON object naming
     * {@code id} as its writer, with {@code seq} counting up from 0, and no CR before the LF.
     */
    private static FileCheck checkFile(Path file, String id, int expectedLines) throws IOException {
        if (!Files.exists(file)) {
            return new FileCheck(0, 0, "file missing");
        }
        byte[] bytes = Files.readAllBytes(file);
        String text = new String(bytes, StandardCharsets.UTF_8);
        int count = 0;
        int from = 0;
        String writerField = "\"writer\":\"" + id + "\"";
        String problem = null;
        while (from < text.length()) {
            int nl = text.indexOf('\n', from);
            if (nl < 0) {
                problem = problem != null ? problem : "last line has no line separator";
                nl = text.length();
            }
            String line = text.substring(from, nl);
            if (problem == null) {
                if (line.endsWith("\r")) {
                    problem = "line " + count + " ends with CR";
                } else if (!line.startsWith("{") || !line.endsWith("}")) {
                    problem = "line " + count + " is not a JSON object: " + line;
                } else if (!line.contains(writerField)) {
                    problem = "line " + count + " names another writer: " + line;
                } else if (seqOf(line) != count) {
                    problem = "line " + count + " has seq " + seqOf(line) + ": " + line;
                } else if (!payloadOk(line)) {
                    problem = "line " + count + " has no payload of " + PAYLOAD_CHARS + " hex digits: " + line;
                }
            }
            count++;
            from = nl + 1;
        }
        if (problem == null && count != expectedLines) {
            problem = count + " lines, expected " + expectedLines;
        }
        return new FileCheck(count, bytes.length, problem);
    }

    /** True if the line's {@code payload} is exactly {@link #PAYLOAD_CHARS} lower-case hex digits. */
    private static boolean payloadOk(String line) {
        String field = "\"payload\":\"";
        int at = line.indexOf(field);
        if (at < 0) {
            return false;
        }
        int from = at + field.length();
        int to = from + PAYLOAD_CHARS;
        if (to >= line.length() || line.charAt(to) != '"') {
            return false;
        }
        for (int i = from; i < to; i++) {
            char c = line.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static long seqOf(String line) {
        int at = line.indexOf("\"seq\":");
        if (at < 0) {
            return -1;
        }
        int from = at + 6;
        int to = from;
        while (to < line.length() && Character.isDigit(line.charAt(to))) {
            to++;
        }
        return to == from ? -1 : Long.parseLong(line.substring(from, to));
    }

    /** CPU time of the pool's worker thread (there should be exactly one), or -1 if it can't be read. */
    private static long workerCpuNanos() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        if (!threads.isThreadCpuTimeSupported()) {
            return -1;
        }
        long total = -1;
        for (ThreadInfo info : threads.getThreadInfo(threads.getAllThreadIds())) {
            if (info != null && info.getThreadName().startsWith("efficient-logger-") && info.getThreadName().contains("-worker-")) {
                long cpu = threads.getThreadCpuTime(info.getThreadId());
                if (cpu >= 0) {
                    total = Math.max(0, total) + cpu;
                }
            }
        }
        return total;
    }

    /** JVM uptime at a nanoTime reading, for matching slow calls against the JVM's own logs. */
    private static final long UPTIME_AT_NANOTIME_ZERO =
            ManagementFactory.getRuntimeMXBean().getUptime() - System.nanoTime() / 1_000_000;

    /** The p-th percentile of the first n sorted values, in microseconds. */
    private static double pct(long[] sorted, int n, double p) {
        int index = (int) Math.ceil(p * n) - 1;
        return sorted[Math.max(0, Math.min(n - 1, index))] / 1e3;
    }

    private static double sec(long at, long start) {
        return (at - start) / 1e9;
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1e6);
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
    }

    private static long gcCount() {
        long n = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            n += Math.max(0, gc.getCollectionCount());
        }
        return n;
    }

    private static long gcMillis() {
        long n = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            n += Math.max(0, gc.getCollectionTime());
        }
        return n;
    }

    private static void clean(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    if (Files.isRegularFile(f) && f.getFileName().toString().startsWith("writer-")) {
                        Files.delete(f);
                    }
                }
            }
        }
    }

    private static long countFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().startsWith("writer-")).count();
        }
    }
}
