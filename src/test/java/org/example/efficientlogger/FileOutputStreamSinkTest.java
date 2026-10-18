package org.example.efficientlogger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The real file system. */
class FileOutputStreamSinkTest {

    private static final SinkFactory FILES = FileOutputStreamSink.FACTORY;

    @TempDir
    Path dir;

    @Test
    void createsTheFileThenAppends() throws IOException {
        Path file = dir.resolve("a.json");
        assertEquals(-1, FILES.lastByte(file), "missing");

        write(file, "ab\n");
        assertEquals('\n', FILES.lastByte(file));

        write(file, "cd");
        assertEquals("ab\ncd", Files.readString(file, UTF_8));
        assertEquals('d', FILES.lastByte(file));
    }

    @Test
    void lastByteOfAnEmptyFileIsMinusOne() throws IOException {
        Path file = Files.createFile(dir.resolve("empty.json"));
        assertEquals(-1, FILES.lastByte(file));
    }

    @Test
    void lastByteIsUnsigned() throws IOException {
        Path file = dir.resolve("e.json");
        write(file, "é"); // C3 A9
        assertEquals(0xA9, FILES.lastByte(file));
    }

    @Test
    void createsMissingDirectories() throws IOException {
        Path nested = dir.resolve("x").resolve("y");
        assertThrows(IOException.class, () -> FILES.open(nested.resolve("a.json")));

        assertTrue(FILES.createDirectoryIfMissing(nested));
        assertTrue(Files.isDirectory(nested));
        assertFalse(FILES.createDirectoryIfMissing(nested));

        write(nested.resolve("a.json"), "{}\n");
        assertEquals("{}\n", Files.readString(nested.resolve("a.json"), UTF_8));
    }

    private static void write(Path file, String text) throws IOException {
        Sink sink = FILES.open(file);
        try {
            byte[] bytes = text.getBytes(UTF_8);
            sink.write(bytes, 0, bytes.length);
        } finally {
            sink.close();
        }
    }
}
