package org.example.efficientlogger;

/**
 * Allocation-free encoding of log messages into caller-owned byte arrays: UTF-8, except for the
 * characters that could break a line or reach a terminal as a control code.
 *
 * <ul>
 *   <li>CR and LF become a space, so a message can never span lines. Valid JSON has them only as
 *       whitespace between tokens (inside strings they must be escaped), so this never changes
 *       what valid JSON means.
 *   <li>The other C0 controls except tab, the C1 controls (U+0080-U+009F), U+2028 and U+2029
 *       become {@code \}{@code uXXXX} escapes. In a JSON string an escape means the same as the
 *       raw character, and outside a string the raw character isn't valid JSON anyway.
 *   <li>Tab and DEL are kept: tab is JSON whitespace, and DEL is allowed in JSON strings.
 *   <li>An unpaired surrogate becomes {@code '?'}, as {@code String.getBytes(UTF_8)} does.
 * </ul>
 *
 * Everything else is byte-for-byte what {@code s.getBytes(StandardCharsets.UTF_8)} produces.
 */
final class Utf8 {

    /** Bytes an escaped character takes: a backslash, 'u' and 4 hex digits. */
    static final int ESCAPE_BYTES = 6;

    private static final byte[] HEX = {
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    };

    private Utf8() {}

    /**
     * Encodes {@code s}, starting at char index {@code from}, into {@code dst[pos..limit)}. Stops
     * before the first character that does not fit, so a code point (including a surrogate pair)
     * or an escape is never split across calls.
     *
     * <p>With at least {@link #ESCAPE_BYTES} bytes free, at least one char is always consumed
     * (unless {@code from} is already the end of the string), so a caller that empties the buffer
     * between calls always makes progress.
     *
     * @return the next char index to encode and the end position in {@code dst}, packed into one
     *     long; read them with {@link #nextChar(long)} and {@link #endPos(long)}
     */
    static long encode(String s, int from, byte[] dst, int pos, int limit) {
        assert from >= 0 && from <= s.length();
        assert pos >= 0 && pos <= limit && limit <= dst.length;
        final int len = s.length();
        int i = from;
        int p = pos;
        while (i < len) {
            // Run of printable ASCII (0x20-0x7F), one byte per char, so at most (limit - p) chars fit.
            int end = (int) Math.min(len, (long) i + (limit - p));
            char c;
            while (i < end && (c = s.charAt(i)) < 0x80 && c >= 0x20) {
                dst[p++] = (byte) c;
                i++;
            }
            if (i == len) {
                break;
            }
            c = s.charAt(i);
            if (c < 0x80) {
                if (c >= 0x20) {
                    break; // printable ASCII, but the buffer is full
                }
                if (c == '\n' || c == '\r' || c == '\t') {
                    if (p == limit) {
                        break;
                    }
                    dst[p++] = (byte) (c == '\t' ? '\t' : ' ');
                } else {
                    if (limit - p < ESCAPE_BYTES) {
                        break;
                    }
                    p = putEscape(c, dst, p);
                }
                i++;
            } else if (c < 0xA0 || c == 0x2028 || c == 0x2029) {
                if (limit - p < ESCAPE_BYTES) {
                    break;
                }
                p = putEscape(c, dst, p);
                i++;
            } else if (c < 0x800) {
                if (limit - p < 2) {
                    break;
                }
                dst[p++] = (byte) (0xC0 | (c >> 6));
                dst[p++] = (byte) (0x80 | (c & 0x3F));
                i++;
            } else if (!Character.isSurrogate(c)) {
                if (limit - p < 3) {
                    break;
                }
                dst[p++] = (byte) (0xE0 | (c >> 12));
                dst[p++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                dst[p++] = (byte) (0x80 | (c & 0x3F));
                i++;
            } else if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(s.charAt(i + 1))) {
                if (limit - p < 4) {
                    break;
                }
                int cp = Character.toCodePoint(c, s.charAt(i + 1));
                dst[p++] = (byte) (0xF0 | (cp >> 18));
                dst[p++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
                dst[p++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
                dst[p++] = (byte) (0x80 | (cp & 0x3F));
                i += 2;
            } else {
                // Unpaired surrogate. Like String.getBytes, write '?' and leave the next char alone.
                if (p == limit) {
                    break;
                }
                dst[p++] = (byte) '?';
                i++;
            }
        }
        return ((long) i << 32) | p;
    }

    /** Writes {@code \}{@code uXXXX} for {@code c} at {@code p}; returns the position after it. */
    private static int putEscape(char c, byte[] dst, int p) {
        dst[p] = '\\';
        dst[p + 1] = 'u';
        dst[p + 2] = HEX[c >> 12];
        dst[p + 3] = HEX[(c >> 8) & 0xF];
        dst[p + 4] = HEX[(c >> 4) & 0xF];
        dst[p + 5] = HEX[c & 0xF];
        return p + ESCAPE_BYTES;
    }

    /** The next char index to encode, from a result of {@link #encode}. */
    static int nextChar(long encodeResult) {
        return (int) (encodeResult >>> 32);
    }

    /** The end position in the destination array, from a result of {@link #encode}. */
    static int endPos(long encodeResult) {
        return (int) encodeResult;
    }

    /**
     * The number of bytes {@code s.getBytes(UTF_8)} produces: plain UTF-8, with no escaping. Used
     * for file names.
     */
    static long utf8Length(String s) {
        final int len = s.length();
        long n = len;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                continue;
            }
            if (c < 0x800) {
                n += 1;
            } else if (!Character.isSurrogate(c)) {
                n += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(s.charAt(i + 1))) {
                n += 2; // 4 bytes for 2 chars
                i++;
            }
            // An unpaired surrogate is one byte ('?'), already counted.
        }
        return n;
    }
}
