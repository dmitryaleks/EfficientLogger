package org.example.efficientlogger;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/**
 * Seams for tests. {@link EfficientLoggerBuilder#build()} uses the defaults, except that it passes
 * the process's shared {@link ProcessLimits}; a {@code TestHooks} made without one gets a fresh
 * instance, which keeps tests apart, as a private pool does.
 */
final class TestHooks {

    SinkFactory sinks = FileOutputStreamSink.FACTORY;
    LongSupplier nanoClock = System::nanoTime;
    /** How minFreeDiskSpace reads the free space; null means the log directory's file store. */
    DiskMark.Probe freeSpace = null;
    /** The process-wide limits the logger shares: see the class comment. */
    ProcessLimits limits;

    TestHooks() {
        this(new ProcessLimits());
    }

    TestHooks(ProcessLimits limits) {
        this.limits = limits;
    }

    /**
     * The key of the hash that picks each ID's worker or stripe (see {@link Hashing#bucket}).
     * Random per logger by default, so IDs from untrusted input can't be crafted to all land on
     * one worker or one lock; tests set a fixed value for predictable routing.
     */
    long shardKey = ThreadLocalRandom.current().nextLong();

    /** Async: false leaves the workers to the test, which runs them with {@code Worker.runOnce}. */
    boolean startThreads = true;
    /** Async: the rings' first position; near 2^31 exercises the int cast of positions. */
    long ringStartIndex = 0;
    /** Async: overrides the batch size when positive. */
    int batchSize = 0;
    /** Async: if set, each worker runs it before every batch; tests block in it to hold a worker. */
    Runnable beforeBatch = null;
}
