package org.example.efficientlogger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HashingTest {

    private static final long KEY = 0x5DEECE66DL; // any fixed key: these tests are deterministic

    // ---- bucket: what the loggers use ----

    @Test
    void bucketStaysInRange() {
        Random rnd = new Random(42);
        for (int buckets = 1; buckets <= 16; buckets++) {
            for (int i = 0; i < 2000; i++) {
                String id = i % 2 == 0 ? "id-" + rnd.nextInt() : "";
                int b = Hashing.bucket(id, rnd.nextLong(), buckets);
                assertTrue(b >= 0 && b < buckets, id + " -> " + b + " of " + buckets);
            }
        }
    }

    @Test
    void oneBucketIsAlwaysZero() {
        Random rnd = new Random(42);
        for (int i = 0; i < 1000; i++) {
            assertEquals(0, Hashing.bucket("id-" + rnd.nextInt(), rnd.nextLong(), 1));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5, 7, 8, 12, 16})
    void spreadsSimilarIdsEvenly(int buckets) {
        int n = 16_000;
        int[] counts = new int[buckets];
        for (int i = 0; i < n; i++) {
            counts[Hashing.bucket("id-" + i, KEY, buckets)]++;
        }
        double mean = (double) n / buckets; // at least 1000, so 20% is over six standard deviations
        for (int c : counts) {
            assertTrue(Math.abs(c - mean) <= 0.2 * mean, "uneven: " + Arrays.toString(counts));
        }
    }

    /**
     * String.hashCode() collisions are easy to make in bulk and are the same whatever is mixed in
     * afterwards, so routing by that hash let a caller pin one worker or one sync lock for every
     * seed (audit F18, review R5). A keyed hash of the characters spreads them.
     */
    @Test
    void idsWithEqualHashCodesAreSpreadOverBuckets() {
        List<String> ids = collidingIds(6); // 64 ids, one hash code
        assertEquals(1, ids.stream().map(String::hashCode).distinct().count(), "the fixture collides");
        for (long key : new long[] {0, KEY, -1, Long.MIN_VALUE, 0x9E3779B97F4A7C15L}) {
            Set<Integer> used = new HashSet<>();
            for (String id : ids) {
                used.add(Hashing.bucket(id, key, 16));
            }
            assertTrue(used.size() >= 8, "key " + key + ": 64 colliding ids used only buckets " + used);
        }
    }

    @Test
    void theKeyChangesTheMapping() {
        int moved = 0;
        for (int i = 0; i < 100; i++) {
            if (Hashing.bucket("tenant", i, 16) != Hashing.bucket("tenant", i + 1000, 16)) {
                moved++;
            }
        }
        assertTrue(moved > 70, "only " + moved + " of 100 key changes moved the id (about 94 expected)");
    }

    @Test
    void hashDependsOnEveryCharacterAndOnTheLength() {
        long base = Hashing.hash("tenant-42", KEY);
        assertEquals(base, Hashing.hash(new String("tenant-42".toCharArray()), KEY), "equal strings hash alike");
        assertNotEquals(base, Hashing.hash("tenant-43", KEY));
        assertNotEquals(base, Hashing.hash("Tenant-42", KEY));
        assertNotEquals(base, Hashing.hash("tenant-42 ", KEY));
        assertNotEquals(base, Hashing.hash("tenant-4", KEY));
        assertNotEquals(base, Hashing.hash("tenant-42", KEY + 1));
        assertNotEquals(Hashing.hash("", KEY), Hashing.hash("", KEY + 1), "even the empty id depends on the key");
    }

    // ---- index: the final mapping from 32 bits to a bucket ----

    @Test
    void indexStaysInRange() {
        int[] edges = {0, 1, -1, Integer.MIN_VALUE, Integer.MAX_VALUE};
        Random rnd = new Random(42);
        for (int buckets = 1; buckets <= 16; buckets++) {
            for (int i = 0; i < 10_000; i++) {
                int hash = i < edges.length ? edges[i] : rnd.nextInt();
                int index = Hashing.index(hash, buckets);
                assertTrue(index >= 0 && index < buckets, "hash " + hash + " -> " + index + " of " + buckets);
            }
        }
    }

    @Test
    void forPowersOfTwoIndexIsTheTopBitsOfTheMixedHash() {
        Random rnd = new Random(42);
        for (int bits = 1; bits <= 8; bits++) {
            for (int i = 0; i < 1000; i++) {
                int hash = rnd.nextInt();
                assertEquals((hash * 0x9E3779B9) >>> (32 - bits), Hashing.index(hash, 1 << bits));
            }
        }
    }

    // ---- fixtures ----

    /**
     * 2^pairs strings of 2*pairs chars that all have the same String.hashCode(): each position holds
     * "Aa" or "BB", which collide, and the hash of a concatenation depends only on its parts' hashes.
     */
    static List<String> collidingIds(int pairs) {
        List<String> ids = new ArrayList<>(List.of(""));
        for (int p = 0; p < pairs; p++) {
            List<String> next = new ArrayList<>(ids.size() * 2);
            for (String s : ids) {
                next.add(s + "Aa");
                next.add(s + "BB");
            }
            ids = next;
        }
        return ids;
    }
}
