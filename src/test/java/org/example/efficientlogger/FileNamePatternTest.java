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
        assertTrue(longReason.contains("(10005 chars)"), longReason);
    }
}
