package org.example.efficientlogger;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** An in-memory file system that records every write and can be told to fail. Single-threaded. */
final class FakeSinks implements SinkFactory {

    record Write(Path path, String text, int bytes) {}

    /** Throw {@code error}, after writing the first {@code bytesFirst} bytes (for a write). */
    private record Failure(Throwable error, int bytesFirst) {}

    private final Map<Path, ByteArrayOutputStream> contents = new HashMap<>();
    private final Set<Path> directories = new HashSet<>();
    private final Map<Path, ArrayDeque<Failure>> openFailures = new HashMap<>();
    private final Map<Path, ArrayDeque<Failure>> writeFailures = new HashMap<>();
    private final Map<Path, ArrayDeque<Failure>> closeFailures = new HashMap<>();

    final List<Write> writes = new ArrayList<>();
    int opens;
    int openNow;
    int maxOpenNow;
    /** Opening fails while this many are open, like the OS limit on file descriptors... */
    int osOpenLimit = Integer.MAX_VALUE;
    /** ...with this message (this one is Unix's). */
    String osOpenLimitMessage = "Too many open files";

    FakeSinks(Path... existingDirectories) {
        directories.addAll(List.of(existingDirectories));
    }

    void preset(Path path, String text) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        content.writeBytes(text.getBytes(UTF_8));
        contents.put(path, content);
    }

    /** The file's text, or null if it doesn't exist. */
    String content(Path path) {
        ByteArrayOutputStream content = contents.get(path);
        return content == null ? null : content.toString(UTF_8);
    }

    boolean directoryExists(Path dir) {
        return directories.contains(dir);
    }

    /**
     * Deletes {@code dir} and the files in it, like {@code rm -r} on Unix: a file still open keeps
     * working, but what is written to it is gone.
     */
    void deleteDirectory(Path dir) {
        directories.remove(dir);
        contents.keySet().removeIf(path -> dir.equals(path.getParent()));
    }

    /** The next open of {@code path} throws {@code e}: an IOException or an unchecked exception. */
    void failNextOpen(Path path, Throwable e) {
        add(openFailures, path, e, 0);
    }

    /** The next write to {@code path} throws {@code e}: an IOException, an unchecked exception or an Error. */
    void failNextWrite(Path path, Throwable e) {
        add(writeFailures, path, e, 0);
    }

    /** The next write to {@code path} writes only its first {@code bytes} bytes, then throws, like a full disk. */
    void failNextWriteAfter(Path path, int bytes, Throwable e) {
        add(writeFailures, path, e, bytes);
    }

    /** The next close of {@code path} throws {@code e} after closing the file, as the OS does. */
    void failNextClose(Path path, Throwable e) {
        add(closeFailures, path, e, 0);
    }

    @Override
    public Sink open(Path path) throws IOException {
        Failure failure = poll(openFailures, path);
        if (failure != null) {
            throw rethrow(failure.error());
        }
        if (!directories.contains(path.getParent())) {
            throw new FileNotFoundException(path + " (No such file or directory)");
        }
        if (openNow >= osOpenLimit) {
            throw new FileNotFoundException(path + " (" + osOpenLimitMessage + ")");
        }
        ByteArrayOutputStream content = contents.computeIfAbsent(path, p -> new ByteArrayOutputStream());
        opens++;
        openNow++;
        maxOpenNow = Math.max(maxOpenNow, openNow);
        return new FakeSink(path, content);
    }

    @Override
    public int lastByte(Path path) {
        ByteArrayOutputStream content = contents.get(path);
        if (content == null || content.size() == 0) {
            return -1;
        }
        byte[] bytes = content.toByteArray();
        return bytes[bytes.length - 1] & 0xFF;
    }

    @Override
    public boolean createDirectoryIfMissing(Path dir) {
        return directories.add(dir);
    }

    private static void add(Map<Path, ArrayDeque<Failure>> failures, Path path, Throwable e, int bytesFirst) {
        if (!(e instanceof IOException) && !(e instanceof RuntimeException) && !(e instanceof Error)) {
            throw new IllegalArgumentException("can only throw IOException, unchecked exceptions or errors");
        }
        failures.computeIfAbsent(path, p -> new ArrayDeque<>()).add(new Failure(e, bytesFirst));
    }

    private static Failure poll(Map<Path, ArrayDeque<Failure>> failures, Path path) {
        ArrayDeque<Failure> queue = failures.get(path);
        return queue == null ? null : queue.poll();
    }

    /** Throws {@code e} as what it is; returns only to satisfy the compiler. */
    private static IOException rethrow(Throwable e) {
        if (e instanceof RuntimeException) {
            throw (RuntimeException) e;
        }
        if (e instanceof Error) {
            throw (Error) e;
        }
        return (IOException) e;
    }

    private final class FakeSink implements Sink {
        private final Path path;
        private final ByteArrayOutputStream content;
        private boolean closed;

        FakeSink(Path path, ByteArrayOutputStream content) {
            this.path = path;
            this.content = content;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (closed) {
                throw new AssertionError("write after close: " + path);
            }
            Failure failure = poll(writeFailures, path);
            if (failure != null) {
                int n = Math.min(failure.bytesFirst(), len);
                if (n > 0) {
                    record(b, off, n);
                }
                throw rethrow(failure.error());
            }
            record(b, off, len);
        }

        private void record(byte[] b, int off, int len) {
            content.write(b, off, len);
            writes.add(new Write(path, new String(b, off, len, UTF_8), len));
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                throw new AssertionError("closed twice: " + path);
            }
            closed = true;
            openNow--;
            Failure failure = poll(closeFailures, path);
            if (failure != null) {
                throw rethrow(failure.error());
            }
        }
    }
}
