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

/** An in-memory file system that records every write and can be told to fail. */
final class FakeSinks implements SinkFactory {

    record Write(Path path, String text, int bytes) {}

    private final Map<Path, ByteArrayOutputStream> contents = new HashMap<>();
    private final Set<Path> directories = new HashSet<>();
    private final Map<Path, ArrayDeque<IOException>> openFailures = new HashMap<>();
    private final Map<Path, ArrayDeque<Exception>> writeFailures = new HashMap<>();

    final List<Write> writes = new ArrayList<>();
    int opens;
    int openNow;
    int maxOpenNow;
    /** Opening fails with "Too many open files" while this many are open, like the OS fd limit. */
    int osOpenLimit = Integer.MAX_VALUE;

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

    void failNextOpen(Path path, IOException e) {
        openFailures.computeIfAbsent(path, p -> new ArrayDeque<>()).add(e);
    }

    /** The next write to {@code path} throws {@code e}: an IOException, or an unchecked exception. */
    void failNextWrite(Path path, Exception e) {
        if (!(e instanceof IOException) && !(e instanceof RuntimeException)) {
            throw new IllegalArgumentException("a write can only throw IOException or unchecked exceptions");
        }
        writeFailures.computeIfAbsent(path, p -> new ArrayDeque<>()).add(e);
    }

    @Override
    public Sink open(Path path) throws IOException {
        IOException failure = poll(openFailures, path);
        if (failure != null) {
            throw failure;
        }
        if (!directories.contains(path.getParent())) {
            throw new FileNotFoundException(path + " (No such file or directory)");
        }
        if (openNow >= osOpenLimit) {
            throw new FileNotFoundException(path + " (Too many open files)");
        }
        contents.computeIfAbsent(path, p -> new ByteArrayOutputStream());
        opens++;
        openNow++;
        maxOpenNow = Math.max(maxOpenNow, openNow);
        return new FakeSink(path);
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

    private static <E extends Exception> E poll(Map<Path, ArrayDeque<E>> failures, Path path) {
        ArrayDeque<E> queue = failures.get(path);
        return queue == null ? null : queue.poll();
    }

    private final class FakeSink implements Sink {
        private final Path path;
        private boolean closed;

        FakeSink(Path path) {
            this.path = path;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (closed) {
                throw new AssertionError("write after close: " + path);
            }
            Exception failure = poll(writeFailures, path);
            if (failure instanceof IOException) {
                throw (IOException) failure;
            }
            if (failure != null) {
                throw (RuntimeException) failure;
            }
            contents.get(path).write(b, off, len);
            writes.add(new Write(path, new String(b, off, len, UTF_8), len));
        }

        @Override
        public void close() {
            if (closed) {
                throw new AssertionError("closed twice: " + path);
            }
            closed = true;
            openNow--;
        }
    }
}
