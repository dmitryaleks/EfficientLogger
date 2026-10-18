package org.example.efficientlogger;

import java.util.function.LongSupplier;

/** Seams for tests. {@link EfficientLoggerBuilder#build()} uses the defaults. */
final class TestHooks {

    SinkFactory sinks = FileOutputStreamSink.FACTORY;
    LongSupplier nanoClock = System::nanoTime;

    /** Async: false leaves the workers to the test, which runs them with {@code Worker.runOnce}. */
    boolean startThreads = true;
    /** Async: the rings' first position; near 2^31 exercises the int cast of positions. */
    long ringStartIndex = 0;
    /** Async: overrides the batch size when positive. */
    int batchSize = 0;
}
