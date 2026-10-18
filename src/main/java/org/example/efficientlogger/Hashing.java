package org.example.efficientlogger;

/** Spreads IDs over workers (async) or stripes (sync). */
final class Hashing {

    private Hashing() {}

    /**
     * Maps a hash to {@code [0, buckets)}. Multiplying by 2^32 divided by the golden ratio spreads
     * similar hashes, like those of "id-1" and "id-2", over the high bits. Multiplying those 32
     * bits by {@code buckets} and keeping the top half then picks a bucket without division, for
     * any bucket count, including 1.
     */
    static int index(int hash, int buckets) {
        return (int) (((hash * 0x9E3779B9) & 0xFFFFFFFFL) * buckets >>> 32);
    }
}
