package org.example.efficientlogger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HashingTest {

    @Test
    void staysInRange() {
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
    void oneBucketIsAlwaysZero() {
        Random rnd = new Random(42);
        for (int i = 0; i < 1000; i++) {
            assertEquals(0, Hashing.index(rnd.nextInt(), 1));
        }
    }

    @Test
    void forPowersOfTwoItIsTheTopBitsOfTheMixedHash() {
        Random rnd = new Random(42);
        for (int bits = 1; bits <= 8; bits++) {
            for (int i = 0; i < 1000; i++) {
                int hash = rnd.nextInt();
                assertEquals((hash * 0x9E3779B9) >>> (32 - bits), Hashing.index(hash, 1 << bits));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 12, 16})
    void spreadsSimilarIdsEvenly(int buckets) {
        int[] counts = new int[buckets];
        for (int i = 0; i < 500; i++) {
            counts[Hashing.index(("id-" + i).hashCode(), buckets)]++;
        }
        double mean = 500.0 / buckets;
        for (int c : counts) {
            assertTrue(Math.abs(c - mean) <= 0.3 * mean, "uneven: " + Arrays.toString(counts));
        }
    }
}
