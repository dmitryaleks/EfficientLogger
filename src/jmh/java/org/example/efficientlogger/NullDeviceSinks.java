package org.example.efficientlogger;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * EfficientLogger's real write path, but every file is the OS null device: the write system
 * calls happen, and no disk is involved, so a benchmark measures the logger rather than the disk.
 *
 * <p>The production {@link FileOutputStreamSink} rightly refuses a device, since it isn't a
 * regular file; that check is exactly what this sink sidesteps, so it opens the device itself
 * rather than going through the production factory. {@code NullDeviceSinksTest} checks that a
 * logger on this sink loses nothing, so the benchmark can't silently measure a logger that drops
 * every message.
 */
final class NullDeviceSinks implements SinkFactory {

    static final Path NULL_DEVICE =
            Paths.get(System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");

    @Override
    public Sink open(Path path) throws IOException {
        FileOutputStream out = new FileOutputStream(NULL_DEVICE.toFile(), true);
        return new Sink() {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
                out.close();
            }
        };
    }

    @Override
    public int lastByte(Path path) {
        return MISSING; // nothing to repair on a device
    }

    @Override
    public boolean createDirectoryIfMissing(Path directory) {
        return false;
    }
}
