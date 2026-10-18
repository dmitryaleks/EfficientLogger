package org.example.efficientlogger;

/** Spreads IDs over workers (async) or stripes (sync). */
final class Hashing {

    private static final long K1 = 0x9E3779B97F4A7C15L; // 2^64 divided by the golden ratio
    private static final long K2 = 0xBF58476D1CE4E5B9L; // splitmix64's finalizer constants
    private static final long K3 = 0x94D049BB133111EBL;

    private Hashing() {}

    /**
     * The worker or stripe for {@code id}: a keyed hash of its characters, mapped to
     * {@code [0, buckets)}.
     *
     * <p>Keyed, rather than {@code String.hashCode()} mixed with a seed, because two strings with
     * equal hash codes (easy to make in bulk: {@code "Aa"} and {@code "BB"}, and any concatenation
     * of such pairs) would then share a bucket whatever the seed. Here each character goes through
     * the key, so without knowing it a caller can't choose IDs that land together. The hash isn't a
     * cryptographic MAC, but nothing reveals the key or the mapping. It allocates nothing and costs
     * about a nanosecond per character.
     */
    static int bucket(String id, long key, int buckets) {
        return index((int) (hash(id, key) >>> 32), buckets);
    }

    /** A 64-bit keyed hash of {@code s}: a multiply-xor chain over the chars, then splitmix64's finalizer. */
    static long hash(String s, long key) {
        int len = s.length();
        long h = key ^ (len * K1);
        for (int i = 0; i < len; i++) {
            h = (h ^ s.charAt(i)) * K1;
        }
        h ^= h >>> 30;
        h *= K2;
        h ^= h >>> 27;
        h *= K3;
        return h ^ (h >>> 31);
    }

    /**
     * Maps a 32-bit hash to {@code [0, buckets)}. Multiplying by 2^32 divided by the golden ratio
     * spreads similar hashes, like those of consecutive integers, over the high bits. Multiplying
     * those 32 bits by {@code buckets} and keeping the top half then picks a bucket without
     * division, for any bucket count, including 1.
     */
    static int index(int hash, int buckets) {
        return (int) (((hash * 0x9E3779B9) & 0xFFFFFFFFL) * buckets >>> 32);
    }
}
