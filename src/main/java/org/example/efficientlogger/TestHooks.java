package org.example.efficientlogger;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/** Seams for tests. {@link EfficientLoggerBuilder#build()} uses the defaults. */
final class TestHooks {

    SinkFactory sinks = FileOutputStreamSink.FACTORY;
    LongSupplier nanoClock = System::nanoTime;

    /**
     * Mixed into each ID's hash to pick its worker or stripe. Random per logger by default, so IDs
     * from untrusted input can't be crafted to all land on one worker or one lock; tests set a
     * fixed value (0 leaves the mapping as the bare hash) for predictable routing.
     */
    int shardSeed = ThreadLocalRandom.current().nextInt();

    /** Async: false leaves the workers to the test, which runs them with {@code Worker.runOnce}. */
    boolean startThreads = true;
    /** Async: the rings' first position; near 2^31 exercises the int cast of positions. */
    long ringStartIndex = 0;
    /** Async: overrides the batch size when positive. */
    int batchSize = 0;
    /** Async: if set, each worker runs it before every batch; tests block in it to hold a worker. */
    Runnable beforeBatch = null;
}
