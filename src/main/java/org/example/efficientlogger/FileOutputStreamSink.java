package org.example.efficientlogger;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Set;

/**
 * A file opened in append mode through {@link FileOutputStream}.
 *
 * <p>Not a FileChannel: an interrupt closes a FileChannel, so one interrupted caller in sync mode
 * would break the file for everyone. {@code FileOutputStream.write} ignores interrupts and
 * allocates nothing on the Java heap; writes of up to 8 KiB go through a native stack buffer.
 *
 * <p>Before writing, it refuses anything that is not a regular file. A symlink, FIFO, device or
 * directory left at the path, for example by another user who can write the log directory, would
 * otherwise redirect the writes or block the thread. The checks use a NOFOLLOW stat, so they never
 * open such a file. A regular file could still be swapped for a symlink in the moment between a
 * check and the open, so the log directory should be writable only by the service's own user;
 * {@link Factory#directoryExposedReason} warns when it isn't.
 */
final class FileOutputStreamSink implements Sink {

    /** Creates files and directories with the process umask, as before. */
    static final SinkFactory FACTORY = new Factory(null);

    /** Creates new files with {@code filePermissions}, and new directories with those plus execute. */
    static SinkFactory withPermissions(Set<PosixFilePermission> filePermissions) {
        return new Factory(filePermissions);
    }

    /** True if the default file system has POSIX permissions (so {@link #withPermissions} has effect). */
    static boolean posixSupported() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static final class Factory implements SinkFactory {

        private static final int OTHERS_WRITE = 0002;
        private static final int STICKY = 01000;

        private final Set<PosixFilePermission> filePerms; // null: use the umask
        private final Set<PosixFilePermission> dirPerms;

        Factory(Set<PosixFilePermission> filePermissions) {
            if (filePermissions != null && posixSupported()) {
                this.filePerms = EnumSet.copyOf(filePermissions);
                this.dirPerms = directoryPermissions(this.filePerms);
            } else {
                this.filePerms = null;
                this.dirPerms = null;
            }
        }

        @Override
        public Sink open(Path path) throws IOException {
            if (filePerms != null) {
                try {
                    Files.createFile(path, PosixFilePermissions.asFileAttribute(filePerms));
                    Files.setPosixFilePermissions(path, filePerms); // exact, whatever the umask
                } catch (FileAlreadyExistsException e) {
                    // keep an existing file's own permissions
                }
            }
            FileOutputStream out = new FileOutputStream(path.toFile(), true);
            boolean ok = false;
            try {
                refuseIfNotRegularFile(path); // in case a symlink was swapped in as the file was created
                ok = true;
            } finally {
                if (!ok) {
                    closeQuietly(out);
                }
            }
            return new FileOutputStreamSink(out);
        }

        @Override
        public int lastByte(Path path) throws IOException {
            BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
                return -1; // missing; it will be created
            }
            if (!attrs.isRegularFile()) {
                throw new IOException("refusing to use " + path + ": not a regular file");
            }
            long length = attrs.size();
            if (length == 0) {
                return -1;
            }
            try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
                file.seek(length - 1);
                return file.read();
            } catch (FileNotFoundException e) {
                return -1; // removed since the stat; opening it will report any real problem
            }
        }

        @Override
        public boolean createDirectoryIfMissing(Path dir) throws IOException {
            if (Files.isDirectory(dir)) {
                return false; // an existing directory keeps its own permissions
            }
            if (dirPerms != null) {
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(dirPerms));
                Files.setPosixFilePermissions(dir, dirPerms); // exact, whatever the umask
            } else {
                Files.createDirectories(dir);
            }
            return true;
        }

        @Override
        public String directoryExposedReason(Path directory) {
            // POSIX only: warn if the directory, or any parent, is writable by other users without
            // the sticky bit, so another user could redirect or read the logs.
            for (Path p = directory; p != null; p = p.getParent()) {
                int mode;
                try {
                    mode = (Integer) Files.getAttribute(p, "unix:mode", LinkOption.NOFOLLOW_LINKS);
                } catch (IOException | RuntimeException e) {
                    // not a POSIX file system (UnsupportedOperationException), or the path can't be read
                    return null;
                }
                if ((mode & OTHERS_WRITE) != 0 && (mode & STICKY) == 0) {
                    return "the log directory path " + p + " is writable by other users and not sticky;"
                            + " another user could redirect or read the logs. Restrict it to the service's own user.";
                }
            }
            return null;
        }
    }

    /** A directory needs execute (search) wherever a class may read or write its files. */
    private static Set<PosixFilePermission> directoryPermissions(Set<PosixFilePermission> file) {
        EnumSet<PosixFilePermission> dir = EnumSet.noneOf(PosixFilePermission.class);
        dir.addAll(file);
        if (dir.contains(PosixFilePermission.OWNER_READ) || dir.contains(PosixFilePermission.OWNER_WRITE)) {
            dir.add(PosixFilePermission.OWNER_EXECUTE);
        }
        if (dir.contains(PosixFilePermission.GROUP_READ) || dir.contains(PosixFilePermission.GROUP_WRITE)) {
            dir.add(PosixFilePermission.GROUP_EXECUTE);
        }
        if (dir.contains(PosixFilePermission.OTHERS_READ) || dir.contains(PosixFilePermission.OTHERS_WRITE)) {
            dir.add(PosixFilePermission.OTHERS_EXECUTE);
        }
        return dir;
    }

    private static void refuseIfNotRegularFile(Path path) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile()) {
            throw new IOException("refusing to write to " + path + ": not a regular file");
        }
    }

    private static void closeQuietly(FileOutputStream out) {
        try {
            out.close();
        } catch (IOException ignored) {
            // already refusing the file
        }
    }

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
