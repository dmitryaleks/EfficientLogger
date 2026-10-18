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
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
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
        assertEquals(SinkFactory.MISSING, FILES.lastByte(file));

        write(file, "ab\n");
        assertEquals('\n', FILES.lastByte(file));

        write(file, "cd");
        assertEquals("ab\ncd", Files.readString(file, UTF_8));
        assertEquals('d', FILES.lastByte(file));
    }

    @Test
    void tellsAnEmptyFileFromAMissingOne() throws IOException {
        Path file = Files.createFile(dir.resolve("empty.json"));
        assertEquals(SinkFactory.EMPTY, FILES.lastByte(file));
        Files.delete(file);
        assertEquals(SinkFactory.MISSING, FILES.lastByte(file));
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
    void flagsADirectoryWritableByItsGroupOrByOthers() throws IOException {
        assumeTrue(posix(), "directory modes need a POSIX file system");
        assumeTrue(FILES.directoryExposedReason(dir) == null, "the temporary directory's parents are themselves exposed");

        assertNull(FILES.directoryExposedReason(directory("safe", "rwx------")), "a private directory is fine");
        assertNull(FILES.directoryExposedReason(directory("readable", "rwxr-xr-x")), "readable isn't writable");
        assertReason(directory("group", "rwxrwx---"), "writable by its group and not sticky");
        assertReason(directory("others", "rwxr-xrwx"), "writable by other users and not sticky");
        assertReason(directory("everyone", "rwxrwxrwx"), "writable by its group and other users and not sticky");

        Path sticky = directory("sticky", "rwxrwxrwx");
        Files.setAttribute(sticky, "unix:mode", 01777);
        assertNull(FILES.directoryExposedReason(sticky), "world-writable with the sticky bit, like /tmp, isn't flagged");

        Path inside = Files.createDirectory(directory("parent", "rwxrwx---").resolve("logs"));
        assertReason(inside, "writable by its group", "a parent counts: it can be swapped out from under the logger");
    }

    /** With these permissions set exactly: setPosixFilePermissions isn't subject to the umask. */
    private Path directory(String name, String permissions) throws IOException {
        Path d = Files.createDirectory(dir.resolve(name));
        Files.setPosixFilePermissions(d, PosixFilePermissions.fromString(permissions));
        return d;
    }

    private static void assertReason(Path directory, String expected) {
        assertReason(directory, expected, "");
    }

    private static void assertReason(Path directory, String expected, String why) {
        String reason = FILES.directoryExposedReason(directory);
        assertNotNull(reason, directory + " should be flagged. " + why);
        assertTrue(reason.contains(expected), reason);
    }

    /** Java reports an unreadable file like a missing one; the two must not be confused (review Q1). */
    @Test
    void refusesAFileItCannotRead() throws IOException {
        assumeTrue(posix(), "file modes need a POSIX file system");
        Path file = Files.writeString(dir.resolve("a.json"), "abc"); // no final newline: the repair would be needed
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("-w-------"));
        assumeTrue(!Files.isReadable(file), "running as root, which can read anything");
        try {
            IOException e = assertThrows(IOException.class, () -> FILES.lastByte(file));
            assertTrue(e.getMessage().startsWith("can't read " + file), e.getMessage());
        } finally {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
    }

    /**
     * Creating a file with permissions must never chmod through a symlink: the permissions go on at
     * creation, with no chmod afterwards that a swapped-in link could redirect (review Q2).
     */
    @Test
    void withPermissionsRefusesASymlinkAndLeavesItsTargetAlone() throws IOException {
        assumeTrue(posix(), "symbolic links need a POSIX file system");
        Path target = Files.writeString(dir.resolve("target"), "secret\n");
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-rw-r--"));
        Path link = dir.resolve("a.json");
        Files.createSymbolicLink(link, target);
        SinkFactory factory = FileOutputStreamSink.withPermissions(PosixFilePermissions.fromString("rw-------"));

        assertThrows(IOException.class, () -> factory.lastByte(link));
        assertThrows(IOException.class, () -> factory.open(link));

        assertEquals("rw-rw-r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(target)),
                "the target's permissions are untouched");
        assertEquals("secret\n", Files.readString(target, UTF_8), "and so is its content");
    }

    @Test
    void withPermissionsCreatesFilesAndDirectoriesRestricted() throws IOException {
        assumeTrue(posix(), "POSIX permissions only");
        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
        SinkFactory factory = FileOutputStreamSink.withPermissions(perms);

        Path logs = dir.resolve("logs");
        assertTrue(factory.createDirectoryIfMissing(logs));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(logs)));

        Path file = logs.resolve("a.json");
        write(factory, file, "{}\n");
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        assertEquals("{}\n", Files.readString(file, UTF_8));
    }

    @Test
    void withPermissionsLeavesAnExistingFileAlone() throws IOException {
        assumeTrue(posix(), "POSIX permissions only");
        Path file = Files.writeString(dir.resolve("a.json"), "old\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        SinkFactory factory = FileOutputStreamSink.withPermissions(PosixFilePermissions.fromString("rw-------"));
        write(factory, file, "new\n");

        assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)),
                "an existing file keeps its own permissions");
        assertEquals("old\nnew\n", Files.readString(file, UTF_8));
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static void write(Path file, String text) throws IOException {
        write(FILES, file, text);
    }

    private static void write(SinkFactory factory, Path file, String text) throws IOException {
        Sink sink = factory.open(file);
        try {
            byte[] bytes = text.getBytes(UTF_8);
            sink.write(bytes, 0, bytes.length);
        } finally {
            sink.close();
        }
    }
}
