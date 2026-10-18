package org.example.efficientlogger;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Locale;

/**
 * A file-name pattern with exactly one {@code {id}} placeholder, and the rules for which IDs it
 * accepts.
 *
 * <p>The rules are the same on every OS: a name accepted on Linux is also valid on Windows and
 * macOS. An ID can never escape the directory, name a Windows device, or produce a name the OS
 * would silently change (for example by dropping a trailing dot). Nor can it produce a name that
 * is unsafe to handle or show:
 * <ul>
 *   <li>one that a shell command run on {@code *} would take as an option (a leading '-')
 *   <li>one that could be a Windows 8.3 short name, and so another file's alias (a '~')
 *   <li>one with control, invisible formatting or line-separator characters, which can hide
 *       or disguise what the name is, or act on a terminal it is printed to
 * </ul>
 * So a valid file name is safe to print in a warning as it is.
 */
final class FileNamePattern {

    static final String PLACEHOLDER = "{id}";
    static final int MAX_NAME_LENGTH = 255;
    /**
     * The most {@link #normalize} can shorten an ID by: NFC composes at most four code points into
     * one (for example U+03A9 U+0314 U+0342 U+0345 into U+1FAF), and lower-casing never shortens.
     * So an ID longer than this many times the limit can't normalize to fit, and can be rejected
     * before it is copied.
     */
    static final int MAX_NORMALIZATION_SHRINK = 8;

    private static final String RESERVED_CHARS = "/\\:*?\"<>|~";
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

    /** The longest ID that can fit in a file name: any longer one is invalid. */
    int maxIdLength() {
        return MAX_NAME_LENGTH - prefix.length() - suffix.length();
    }

    /**
     * A canonical form of {@code id}: NFC-normalized, then lower-cased. IDs that a case-insensitive
     * or normalization-insensitive file system would treat as one name map to the same canonical
     * form, so they share a file and a worker on purpose rather than by accident. Allocates.
     */
    static String normalize(String id) {
        return Normalizer.normalize(id, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
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
        long nameLength = (long) prefix.length() + id.length() + suffix.length();
        if (nameLength > MAX_NAME_LENGTH) {
            // Checked before building the name, which could be huge.
            return "the file name would be " + nameLength + " chars, longer than " + MAX_NAME_LENGTH + " chars";
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
        if (name.charAt(0) == '-') {
            return "starts with '-'";
        }
        for (int i = 0; i < len; ) {
            char c = name.charAt(i);
            int cp;
            if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(name.charAt(i + 1))) {
                cp = Character.toCodePoint(c, name.charAt(i + 1));
            } else if (Character.isSurrogate(c)) {
                return "contains an unpaired surrogate (" + unicode(c) + ")";
            } else {
                cp = c;
                if (RESERVED_CHARS.indexOf(c) >= 0) {
                    return "contains '" + c + "'";
                }
            }
            String kind = unsafeKind(cp);
            if (kind != null) {
                return "contains " + kind + " (" + unicode(cp) + ")";
            }
            i += Character.charCount(cp);
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

    /**
     * What makes code point {@code cp} unsafe in a file name, or null if nothing does: control
     * characters (C0, DEL and C1), invisible formatting characters (such as bidi overrides,
     * zero-width characters and tag characters), and line and paragraph separators.
     */
    static String unsafeKind(int cp) {
        switch (Character.getType(cp)) {
            case Character.CONTROL:
                return "a control character";
            case Character.FORMAT:
                return "an invisible formatting character";
            case Character.LINE_SEPARATOR:
            case Character.PARAGRAPH_SEPARATOR:
                return "a line or paragraph separator";
            default:
                return null;
        }
    }

    /**
     * {@code s} in quotes for a warning, with long input shortened. Control, formatting and
     * separator characters, and all surrogates, are escaped, so the result can't act on a
     * terminal or disguise itself.
     */
    static String quote(String s) {
        int shown = Math.min(s.length(), MAX_QUOTED_LENGTH);
        StringBuilder sb = new StringBuilder(shown + 24).append('"');
        for (int i = 0; i < shown; i++) {
            char c = s.charAt(i);
            if (Character.isSurrogate(c) || unsafeKind(c) != null) {
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

    /** Java-style escapes for a code point: one for a BMP char, two (a surrogate pair) otherwise. */
    private static String unicode(int cp) {
        if (Character.isBmpCodePoint(cp)) {
            return String.format("\\u%04X", cp);
        }
        return String.format("\\u%04X\\u%04X", (int) Character.highSurrogate(cp), (int) Character.lowSurrogate(cp));
    }
}
