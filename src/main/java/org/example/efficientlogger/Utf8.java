package org.example.efficientlogger;

/**
 * Allocation-free UTF-8 encoding of {@link String}s into caller-owned byte arrays.
 *
 * <p>Output is byte-for-byte identical to {@code s.getBytes(StandardCharsets.UTF_8)}: a valid
 * surrogate pair becomes 4 bytes, and any unpaired surrogate becomes {@code '?'}.
 */
final class Utf8 {

    private Utf8() {}

    /**
     * Encodes {@code s}, starting at char index {@code from}, into {@code dst[pos..limit)}. Stops
     * before the first code point that does not fit, so a code point (including a surrogate
     * pair) is never split across calls.
     *
     * <p>With at least 4 bytes free, at least one char is always consumed (unless {@code from} is
     * already the end of the string), so a caller that empties the buffer between calls always
     * makes progress.
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
            // ASCII run: one byte per char, so at most (limit - p) chars fit.
            int end = (int) Math.min(len, (long) i + (limit - p));
            char c;
            while (i < end && (c = s.charAt(i)) < 0x80) {
                dst[p++] = (byte) c;
                i++;
            }
            if (i == len) {
                break;
            }
            c = s.charAt(i);
            if (c < 0x80) {
                break; // ASCII, but the buffer is full
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

    /** The next char index to encode, from a result of {@link #encode}. */
    static int nextChar(long encodeResult) {
        return (int) (encodeResult >>> 32);
    }

    /** The end position in the destination array, from a result of {@link #encode}. */
    static int endPos(long encodeResult) {
        return (int) encodeResult;
    }

    /** The number of bytes {@link #encode} produces for the whole of {@code s}. */
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
