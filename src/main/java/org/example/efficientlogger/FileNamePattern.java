package org.example.efficientlogger;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * A file-name pattern with exactly one {@code {id}} placeholder, and the rules for which IDs it
 * accepts.
 *
 * <p>The rules are the same on every OS: a name accepted on Linux is also valid on Windows and
 * macOS. An ID can never escape the directory, name a Windows device, or produce a name the OS
 * would silently change (for example by dropping a trailing dot).
 */
final class FileNamePattern {

    static final String PLACEHOLDER = "{id}";
    static final int MAX_NAME_LENGTH = 255;

    private static final String RESERVED_CHARS = "/\\:*?\"<>|";
    private static final int MAX_QUOTED_LENGTH = 100;

    private final Path directory;
    private final String prefix;
    private final String suffix;

    private FileNamePattern(Path directory, String prefix, String suffix) {
        this.directory = directory;
        this.prefix = prefix;
        this.suffix = suffix;
    }

    /**
     * @param directory an absolute, normalized directory
     * @throws IllegalArgumentException if the pattern doesn't have exactly one placeholder, or
     *     can't produce valid file names
     */
    static FileNamePattern parse(Path directory, String pattern) {
        int at = pattern.indexOf(PLACEHOLDER);
        if (at < 0 || pattern.indexOf(PLACEHOLDER, at + PLACEHOLDER.length()) >= 0) {
            throw new IllegalArgumentException(
                    "fileNamePattern must contain exactly one " + PLACEHOLDER + ": " + quote(pattern));
        }
        FileNamePattern p = new FileNamePattern(
                directory, pattern.substring(0, at), pattern.substring(at + PLACEHOLDER.length()));
        // A one-letter sample ID checks everything the pattern contributes: its characters, a
        // trailing dot or space, a reserved device name, and its length.
        String reason = p.invalidReason("a");
        if (reason != null) {
            throw new IllegalArgumentException("invalid fileNamePattern " + quote(pattern) + ": " + reason);
        }
        return p;
    }

    Path directory() {
        return directory;
    }

    /** The file name for {@code id}. Allocates. */
    String fileName(String id) {
        return prefix + id + suffix;
    }

    /** The file for {@code id}. Call only when {@link #invalidReason} returned null. */
    Path path(String id) {
        return directory.resolve(fileName(id));
    }

    /** Why {@code id} can't be used, or null if it can. Allocates; call once per file open. */
    String invalidReason(String id) {
        if (id.isEmpty()) {
            return "the id is empty";
        }
        String name = fileName(id);
        String reason = invalidNameReason(name);
        if (reason != null) {
            return "file name " + quote(name) + " " + reason;
        }
        try {
            if (!directory.equals(directory.resolve(name).getParent())) {
                return "file name " + quote(name) + " resolves outside the directory";
            }
        } catch (InvalidPathException e) {
            return "file name " + quote(name) + " is not a valid path: " + e.getReason();
        }
        return null;
    }

    /** Why {@code name} isn't a portable file name, or null if it is. */
    static String invalidNameReason(String name) {
        int len = name.length();
        if (len == 0) {
            return "is empty";
        }
        if (len > MAX_NAME_LENGTH) {
            return "is longer than " + MAX_NAME_LENGTH + " chars";
        }
        if (Utf8.utf8Length(name) > MAX_NAME_LENGTH) {
            return "is longer than " + MAX_NAME_LENGTH + " bytes in UTF-8";
        }
        if (name.equals(".") || name.equals("..")) {
            return "is '.' or '..'";
        }
        for (int i = 0; i < len; i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return "contains a control character (" + unicode(c) + ")";
            }
            if (RESERVED_CHARS.indexOf(c) >= 0) {
                return "contains '" + c + "'";
            }
            if (Character.isSurrogate(c)) {
                if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(name.charAt(i + 1))) {
                    i++;
                    continue;
                }
                return "contains an unpaired surrogate (" + unicode(c) + ")";
            }
        }
        char last = name.charAt(len - 1);
        if (last == '.' || last == ' ') {
            return "ends with '" + last + "'";
        }
        if (isWindowsDeviceName(name)) {
            return "is a reserved device name on Windows";
        }
        return null;
    }

    /**
     * CON, PRN, AUX, NUL, COM0-9 and LPT0-9 (also with superscript 1-3), in any case, alone or
     * followed by an extension. Windows ignores trailing spaces before the extension.
     */
    private static boolean isWindowsDeviceName(String name) {
        int dot = name.indexOf('.');
        int end = dot < 0 ? name.length() : dot;
        while (end > 0 && name.charAt(end - 1) == ' ') {
            end--;
        }
        if (end == 3) {
            return name.regionMatches(true, 0, "CON", 0, 3)
                    || name.regionMatches(true, 0, "PRN", 0, 3)
                    || name.regionMatches(true, 0, "AUX", 0, 3)
                    || name.regionMatches(true, 0, "NUL", 0, 3);
        }
        if (end == 4) {
            char d = name.charAt(3);
            boolean digit = (d >= '0' && d <= '9') || d == '¹' || d == '²' || d == '³';
            return digit
                    && (name.regionMatches(true, 0, "COM", 0, 3) || name.regionMatches(true, 0, "LPT", 0, 3));
        }
        return false;
    }

    /** {@code s} in quotes, with control chars and surrogates escaped and long input shortened. */
    static String quote(String s) {
        int shown = Math.min(s.length(), MAX_QUOTED_LENGTH);
        StringBuilder sb = new StringBuilder(shown + 24).append('"');
        for (int i = 0; i < shown; i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F || Character.isSurrogate(c)) {
                sb.append(unicode(c));
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
        if (shown < s.length()) {
            sb.append("... (").append(s.length()).append(" chars)");
        }
        return sb.toString();
    }

    private static String unicode(char c) {
        return String.format("\\u%04X", (int) c);
    }
}
