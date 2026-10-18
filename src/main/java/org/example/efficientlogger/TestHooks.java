package org.example.efficientlogger;

import java.util.function.LongSupplier;

/** Seams for tests. {@link EfficientLoggerBuilder#build()} uses the defaults. */
final class TestHooks {

    SinkFactory sinks = FileOutputStreamSink.FACTORY;
    LongSupplier nanoClock = System::nanoTime;
}
