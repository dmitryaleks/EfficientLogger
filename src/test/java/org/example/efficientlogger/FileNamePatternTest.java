package org.example.efficientlogger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class FileNamePatternTest {

    private static final Path DIR = Paths.get("logs").toAbsolutePath().normalize();

    @ParameterizedTest
    @ValueSource(strings = {"{id}", "events-{id}.json", ".{id}", "{id}.log", "app {id} log.json", "x.{id}.y"})
    void acceptsPattern(String pattern) {
        FileNamePattern.parse(DIR, pattern);
    }

    static Stream<Arguments> invalidPatterns() {
        return Stream.of(
                arguments("", "exactly one {id}"),
                arguments("events.json", "exactly one {id}"),
                arguments("{id}-{id}.json", "exactly one {id}"),
                arguments("{ID}.json", "exactly one {id}"),
                arguments("logs/{id}.json", "contains '/'"),
                arguments("logs\\{id}.json", "contains '\\'"),
                arguments("{id}:stream", "contains ':'"),
                arguments("{id}?.json", "contains '?'"),
                arguments("x\t{id}", "control character (\\u0009)"),
                arguments("{id}.", "ends with '.'"),
                arguments("{id} ", "ends with ' '"),
                arguments("NUL.{id}", "reserved device name"),
                arguments("-{id}.json", "starts with '-'"),
                arguments("{id}~1.log", "contains '~'"),
                arguments("a".repeat(255) + "{id}", "longer than 255 chars"));
    }

    @ParameterizedTest
    @MethodSource("invalidPatterns")
    void rejectsPattern(String pattern, String expectedReason) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> FileNamePattern.parse(DIR, pattern));
        assertTrue(e.getMessage().contains(expectedReason), e.getMessage());
    }

    static Stream<Arguments> acceptedIds() {
        return Stream.of(
                arguments("{id}.json", "a"),
                arguments("{id}.json", "user-42"),
                arguments("{id}.json", "a..b"),
                arguments("{id}.json", "x."), // the dot isn't at the end of the name
                arguments("{id}.json", "a b"),
                arguments("{id}.json", "Zoë"),
                arguments("{id}.json", "東京"),
                arguments("{id}.json", "😀"), // surrogate pair
                arguments("{id}.json", "CONSOLE"),
                arguments("{id}.json", "COM10"),
                arguments("{id}.json", "NULL"),
                arguments("{id}", ".hidden"),
                arguments("events-{id}.json", "-rf"), // the name starts with "events-"
                arguments("{id}.json", "a b-c_d.e"),
                arguments("{id}.json", cp(0x645, 0x631, 0x62D, 0x628, 0x627)), // Arabic, right to left
                arguments("{id}.json", cp(0x1F44D, 0x1F3FD)), // emoji with a skin-tone modifier
                arguments("{id}.json", "a" + cp(0xA0) + "b"), // a no-break space is visible as a space
                arguments("{id}.json", "a".repeat(250))); // 255 chars
    }

    @ParameterizedTest
    @MethodSource("acceptedIds")
    void acceptsId(String pattern, String id) {
        assertNull(FileNamePattern.parse(DIR, pattern).invalidReason(id));
    }

    static Stream<Arguments> rejectedIds() {
        return Stream.of(
                arguments("{id}.json", "", "the id is empty"),
                arguments("{id}", ".", "is '.' or '..'"),
                arguments("{id}", "..", "is '.' or '..'"),
                arguments("{id}.json", "../x", "contains '/'"),
                arguments("{id}.json", "a/b", "contains '/'"),
                arguments("{id}.json", "a\\b", "contains '\\'"),
                arguments("{id}.json", "a:b", "contains ':'"),
                arguments("{id}.json", "a*b", "contains '*'"),
                arguments("{id}.json", "a?b", "contains '?'"),
                arguments("{id}.json", "a\"b", "contains '\"'"),
                arguments("{id}.json", "a<b", "contains '<'"),
                arguments("{id}.json", "a>b", "contains '>'"),
                arguments("{id}.json", "a|b", "contains '|'"),
                arguments("{id}.json", "a\u0000b", "control character (\\u0000)"),
                arguments("{id}.json", "a\tb", "control character (\\u0009)"),
                arguments("{id}.json", "a\u007Fb", "control character (\\u007F)"),
                arguments("{id}.json", "a\uD800b", "unpaired surrogate (\\uD800)"),
                arguments("{id}.json", "\uDC00", "unpaired surrogate (\\uDC00)"),
                arguments("{id}", "x.", "ends with '.'"),
                arguments("{id}", "x ", "ends with ' '"),
                arguments("{id}.json", "CON", "reserved device name"),
                arguments("{id}.json", "con", "reserved device name"),
                arguments("{id}.json", "NUL ", "reserved device name"), // "NUL .json"
                arguments("{id}", "PRN", "reserved device name"),
                arguments("{id}.txt", "aux", "reserved device name"),
                arguments("{id}.json", "LPT1", "reserved device name"),
                arguments("{id}.json", "com0", "reserved device name"),
                arguments("{id}.json", "COM¹", "reserved device name"),
                // Option injection into shell commands run on *, and Windows 8.3 short names
                arguments("{id}.json", "-rf", "starts with '-'"),
                arguments("{id}.json", "--use-compress-program=sh x.sh", "starts with '-'"),
                arguments("{id}.log", "VERYLO~1", "contains '~'"),
                arguments("{id}.json", "a~b", "contains '~'"),
                // Characters that hide or disguise a name, or act on a terminal
                arguments("{id}.json", "a" + cp(0x85) + "b", "control character (\\u0085)"),
                arguments("{id}.json", "a" + cp(0x9B) + "b", "control character (\\u009B)"),
                arguments("{id}.json", "invoice" + cp(0x202E) + "nosj.exe", "invisible formatting character (\\u202E)"),
                arguments("{id}.json", "a" + cp(0x2066) + "b", "invisible formatting character (\\u2066)"),
                arguments("{id}.json", "a" + cp(0x200B) + "b", "invisible formatting character (\\u200B)"),
                arguments("{id}.json", cp(0x1F468, 0x200D, 0x1F469), "invisible formatting character (\\u200D)"),
                arguments("{id}.json", "a" + cp(0xFEFF) + "b", "invisible formatting character (\\uFEFF)"),
                arguments("{id}.json", "a" + cp(0xAD) + "b", "invisible formatting character (\\u00AD)"),
                arguments("{id}.json", "a" + cp(0xE0041) + "b", "invisible formatting character (\\uDB40\\uDC41)"),
                arguments("{id}.json", "a" + cp(0x2028) + "b", "line or paragraph separator (\\u2028)"),
                arguments("{id}.json", "a" + cp(0x2029) + "b", "line or paragraph separator (\\u2029)"),
                arguments("{id}.json", "a".repeat(251), "longer than 255 chars"),
                arguments("{id}.json", "東".repeat(84), "longer than 255 bytes")); // 257 bytes, 89 chars
    }

    @ParameterizedTest
    @MethodSource("rejectedIds")
    void rejectsId(String pattern, String id, String expectedReason) {
        String reason = FileNamePattern.parse(DIR, pattern).invalidReason(id);
        assertNotNull(reason);
        assertTrue(reason.contains(expectedReason), reason);
    }

    @Test
    void buildsTheNameAroundTheId() {
        FileNamePattern pattern = FileNamePattern.parse(DIR, "events-{id}.json");
        assertEquals("events-42.json", pattern.fileName("42"));
        assertEquals(DIR.resolve("events-42.json"), pattern.path("42"));
    }

    @Test
    void reasonsEscapeControlCharsAndShortenLongIds() {
        FileNamePattern pattern = FileNamePattern.parse(DIR, "{id}.json");

        String reason = pattern.invalidReason("a\u0000b");
        assertFalse(reason.contains("\u0000"), reason);
        assertTrue(reason.contains("a\\u0000b"), reason);

        String longReason = pattern.invalidReason("x".repeat(10_000));
        assertTrue(longReason.length() < 300, longReason);
        assertTrue(longReason.contains("would be 10005 chars"), longReason);
    }

    @Test
    void knowsTheLongestIdThatFits() {
        assertEquals(255 - "events-".length() - ".json".length(),
                FileNamePattern.parse(DIR, "events-{id}.json").maxIdLength());
        assertEquals(255, FileNamePattern.parse(DIR, "{id}").maxIdLength());
    }

    @Test
    void quoteEscapesWhatCouldActOnATerminalOrDisguiseText() {
        String s = "a" + cp(0x1B) + "b" + cp(0x9B) + "c" + cp(0x202E) + "d" + cp(0x200B) + "e" + cp(0x2028) + "f"
                + cp(0x1F600) + "g" + cp(0xA0) + "h";
        assertEquals("\"a\\u001Bb\\u009Bc\\u202Ed\\u200Be\\u2028f\\uD83D\\uDE00g" + cp(0xA0) + "h\"",
                FileNamePattern.quote(s));
    }

    /** A string of the given code points. */
    private static String cp(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }
}
