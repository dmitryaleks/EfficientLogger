package org.example.efficientlogger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
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

    @Test
    void refusesADirectoryAtThePath() throws IOException {
        Path path = Files.createDirectory(dir.resolve("a.json"));
        assertThrows(IOException.class, () -> FILES.lastByte(path));
        assertThrows(IOException.class, () -> FILES.open(path));
    }

    @Test
    void refusesASymlinkAndLeavesItsTargetUntouched() throws IOException {
        assumeTrue(posix(), "symbolic links need a POSIX file system");
        Path target = Files.writeString(dir.resolve("target"), "secret\n");
        Path link = dir.resolve("a.json");
        Files.createSymbolicLink(link, target);

        assertThrows(IOException.class, () -> FILES.lastByte(link), "must not follow the link to read");
        assertThrows(IOException.class, () -> FILES.open(link), "must not follow the link to write");
        assertEquals("secret\n", Files.readString(target, UTF_8), "the link's target was not touched");
    }

    @Test
    void flagsADirectoryExposedToOtherUsers() throws IOException {
        assumeTrue(posix(), "directory modes need a POSIX file system");
        Path safe = Files.createDirectory(dir.resolve("safe"));
        Files.setPosixFilePermissions(safe, PosixFilePermissions.fromString("rwx------"));
        assertNull(FILES.directoryExposedReason(safe), "a private directory is fine");

        Path exposed = Files.createDirectory(dir.resolve("exposed"));
        Files.setPosixFilePermissions(exposed, PosixFilePermissions.fromString("rwxrwxrwx"));
        String reason = FILES.directoryExposedReason(exposed);
        assertNotNull(reason, "a world-writable directory should be flagged");
        assertTrue(reason.contains("writable by other users"), reason);
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
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
