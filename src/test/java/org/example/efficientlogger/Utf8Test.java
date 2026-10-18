package org.example.efficientlogger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class Utf8Test {

    private static final long SEED = 20261018L;

    /** Every encoded-length boundary, plus each kind of unpaired surrogate. */
    static Stream<String> edgeCases() {
        return Stream.of(
                "",
                "a",
                "\u007F",
                "\u0080",
                "é", // Latin-1, 2 bytes
                "߿",
                "ࠀ",
                "퟿", // last char before the surrogate range
                "", // first char after it
                "￿",
                "𐀀", // U+10000
                "😀", // U+1F600
                "􏿿", // U+10FFFF
                "\uD800", // lone high
                "\uDC00", // lone low
                "a\uD800", // high at the end
                "\uDC00\uD800", // reversed pair
                "\uD800a", // high followed by ASCII
                "\uD800𐀀", // lone high, then a valid pair
                "{\"name\":\"Zoë\",\"city\":\"東京\",\"mood\":\"😀\"}");
    }

    @ParameterizedTest
    @MethodSource("edgeCases")
    void encodesEdgeCasesLikeStringGetBytes(String s) {
        assertArrayEquals(s.getBytes(UTF_8), encodeAll(s), () -> escape(s));
        assertEquals(s.getBytes(UTF_8).length, Utf8.utf8Length(s), () -> escape(s));
    }

    @Test
    void encodesRandomStringsLikeStringGetBytes() {
        Random rnd = new Random(SEED);
        for (int n = 0; n < 10_000; n++) {
            String s = randomString(rnd, 200, n % 4 == 0);
            byte[] expected = s.getBytes(UTF_8);
            int iteration = n;
            assertArrayEquals(expected, encodeAll(s), () -> "iteration " + iteration + ": " + escape(s));
            assertEquals(expected.length, Utf8.utf8Length(s), () -> "iteration " + iteration + ": " + escape(s));
        }
    }

    @Test
    void chunkedEncodingNeverSplitsACodePoint() {
        List<String> inputs = new ArrayList<>(edgeCases().collect(Collectors.toList()));
        Random rnd = new Random(SEED + 1);
        for (int n = 0; n < 300; n++) {
            inputs.add(randomString(rnd, 120, n % 4 == 0));
        }
        for (String s : inputs) {
            byte[] expected = s.getBytes(UTF_8);
            for (int limit = 4; limit <= 64; limit++) {
                byte[] buf = new byte[limit];
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int from = 0;
                while (from < s.length()) {
                    long r = Utf8.encode(s, from, buf, 0, limit);
                    int next = Utf8.nextChar(r);
                    int end = Utf8.endPos(r);
                    int lim = limit;
                    int start = from;
                    assertTrue(next > start, () -> "no progress at char " + start + ", limit " + lim + ": " + escape(s));
                    assertValidUtf8(buf, end, () -> "chunk split a code point, limit " + lim + ": " + escape(s));
                    out.write(buf, 0, end);
                    from = next;
                }
                int lim = limit;
                assertArrayEquals(expected, out.toByteArray(), () -> "limit " + lim + ": " + escape(s));
            }
        }
    }

    @Test
    void stopsBeforeACodePointThatDoesNotFit() {
        assertEncodes("abc", 2, 2, 2); // ASCII, buffer full
        assertEncodes("abé", 3, 2, 2); // 2-byte char, 1 byte left
        assertEncodes("ab€", 4, 2, 2); // 3-byte char, 2 bytes left
        assertEncodes("ab😀", 5, 2, 2); // 4-byte pair, 3 bytes left: pair not split
        assertEncodes("ab\uD800", 2, 2, 2); // '?' needs 1 byte
    }

    @Test
    void fillsTheBufferExactly() {
        assertEncodes("abé", 4, 3, 4);
        assertEncodes("ab€", 5, 3, 5);
        assertEncodes("ab😀", 6, 4, 6);
        assertEncodes("ab\uD800", 3, 3, 3);
    }

    @Test
    void writesOnlyBetweenPosAndLimit() {
        byte[] dst = new byte[32];
        Arrays.fill(dst, (byte) 0x55);
        String s = "xé€😀yz";

        // From char 1: é (2 bytes) and € (3 bytes) fill [5, 10); the pair needs 4 bytes but only 2 are left.
        long r = Utf8.encode(s, 1, dst, 5, 12);

        assertEquals(3, Utf8.nextChar(r));
        assertEquals(10, Utf8.endPos(r));
        assertArrayEquals("é€".getBytes(UTF_8), Arrays.copyOfRange(dst, 5, 10));
        for (int i = 0; i < dst.length; i++) {
            if (i < 5 || i >= 10) {
                assertEquals((byte) 0x55, dst[i], "byte " + i + " was overwritten");
            }
        }
    }

    @Test
    void noRoomConsumesNothing() {
        long r = Utf8.encode("abc", 1, new byte[8], 4, 4);
        assertEquals(1, Utf8.nextChar(r));
        assertEquals(4, Utf8.endPos(r));
    }

    @Test
    void startingAtTheEndIsANoOp() {
        long r = Utf8.encode("abc", 3, new byte[8], 2, 8);
        assertEquals(3, Utf8.nextChar(r));
        assertEquals(2, Utf8.endPos(r));
    }

    // --- helpers ---

    /** Encodes the whole string in one call, into a buffer that is always big enough. */
    private static byte[] encodeAll(String s) {
        byte[] dst = new byte[s.length() * 3];
        long r = Utf8.encode(s, 0, dst, 0, dst.length);
        assertEquals(s.length(), Utf8.nextChar(r), () -> "not fully encoded: " + escape(s));
        return Arrays.copyOf(dst, Utf8.endPos(r));
    }

    private static void assertEncodes(String s, int room, int expectedNext, int expectedEnd) {
        long r = Utf8.encode(s, 0, new byte[room], 0, room);
        assertEquals(expectedNext, Utf8.nextChar(r), () -> "next char for " + escape(s) + " in " + room + " bytes");
        assertEquals(expectedEnd, Utf8.endPos(r), () -> "end pos for " + escape(s) + " in " + room + " bytes");
    }

    private static void assertValidUtf8(byte[] buf, int len, java.util.function.Supplier<String> message) {
        try {
            UTF_8.newDecoder().decode(ByteBuffer.wrap(buf, 0, len)); // reports malformed input
        } catch (CharacterCodingException e) {
            fail(message.get(), e);
        }
    }

    /**
     * A random mix of ASCII, 2-byte, 3-byte, supplementary and unpaired-surrogate chars. Latin-1
     * strings are compact in the JDK and take a different path inside String.getBytes.
     */
    private static String randomString(Random rnd, int maxLen, boolean latin1Only) {
        int len = rnd.nextInt(maxLen + 1);
        StringBuilder sb = new StringBuilder(len + 1);
        while (sb.length() < len) {
            if (latin1Only) {
                sb.append((char) rnd.nextInt(0x100));
                continue;
            }
            int kind = rnd.nextInt(10);
            if (kind < 5) {
                sb.append((char) rnd.nextInt(0x80));
            } else if (kind == 5) {
                sb.append((char) (0x80 + rnd.nextInt(0x800 - 0x80)));
            } else if (kind == 6) {
                char c;
                do {
                    c = (char) (0x800 + rnd.nextInt(0x10000 - 0x800));
                } while (Character.isSurrogate(c));
                sb.append(c);
            } else if (kind == 7) {
                sb.appendCodePoint(0x10000 + rnd.nextInt(0x110000 - 0x10000));
            } else if (kind == 8) {
                sb.append((char) (Character.MIN_HIGH_SURROGATE + rnd.nextInt(0x400)));
            } else {
                sb.append((char) (Character.MIN_LOW_SURROGATE + rnd.nextInt(0x400)));
            }
        }
        return sb.toString();
    }

    /** Printable form of a test string for failure messages. */
    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c < 0x7F) {
                sb.append(c);
            } else {
                sb.append(String.format("\\u%04X", (int) c));
            }
        }
        return sb.append('"').toString();
    }
}
