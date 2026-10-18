package org.example.efficientlogger;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A file opened in append mode through {@link FileOutputStream}.
 *
 * <p>Not a FileChannel: an interrupt closes a FileChannel, so one interrupted caller in sync mode
 * would break the file for everyone. {@code FileOutputStream.write} ignores interrupts and
 * allocates nothing on the Java heap; writes of up to 8 KiB go through a native stack buffer.
 */
final class FileOutputStreamSink implements Sink {

    static final SinkFactory FACTORY = new SinkFactory() {
        @Override
        public Sink open(Path path) throws IOException {
            return new FileOutputStreamSink(new FileOutputStream(path.toFile(), true));
        }

        @Override
        public int lastByte(Path path) throws IOException {
            try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
                long length = file.length();
                if (length == 0) {
                    return -1;
                }
                file.seek(length - 1);
                return file.read();
            } catch (FileNotFoundException e) {
                return -1; // missing; if it can't be read for another reason, opening it reports that
            }
        }

        @Override
        public boolean createDirectoryIfMissing(Path dir) throws IOException {
            if (Files.isDirectory(dir)) {
                return false;
            }
            Files.createDirectories(dir);
            return true;
        }
    };

    private final FileOutputStream out;

    private FileOutputStreamSink(FileOutputStream out) {
        this.out = out;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
