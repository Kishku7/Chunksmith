package com.kishku7.chunksmith.lod.net;

/**
 * The region freshness token -- derived from (mtime, size), never from the file's contents.
 *
 * <p>Reading the contents was a server-killing bug. Until
 * 3.1.0-beta-4 the server answered every index request with
 * {@code crc.update(Files.readAllBytes(file))} over every
 * region in the client's radius, on the server main thread,
 * and a travelling client re-asks every 5 seconds, so the heap
 * pegged at 100% and the server hung. Deriving the token from
 * (mtime, size) is what removes the read: one {@code statx}
 * per region, no file contents at all. {@code CsLodServerNet}
 * carries the measurements and the other two changes that had
 * to land with this one.
 *
 * <p>A cache-freshness check, not a security boundary (the
 * handshake token is that). Any rewrite moves mtime: the store
 * appends records and rewrites header slots, and even an
 * in-place rewrite of identical length moves it, and a
 * same-millisecond collision is unreachable because the server
 * will not index a region until it has been untouched for
 * {@link CsLodStoreScan#SETTLE_MILLIS} (10 s). It detects
 * CHANGE, not corruption: bad bytes in transit are HTTP's and
 * TCP's problem.
 *
 * <p>Copy or rsync a world without {@code -t} and every
 * region's mtime changes while its contents do not, so every
 * client re-downloads the regions in its radius (~370 MB for
 * the store above). That is the cost of erring in the only
 * safe direction: the failure we refuse is a token that says
 * "unchanged" about a region that changed.
 *
 * <p>The client never recomputes this; it cannot, the mtime of
 * its own copy is when the client wrote it. The token is
 * opaque to it (see {@code CsLodManifest}).
 */
public final class CsLodRegionHash {

    private CsLodRegionHash() {
    }

    /**
     * Returns the token for a region file with this mtime and
     * length. Both inputs are folded through a 64-bit
     * avalanche (the SplitMix64 finalizer) so the two fields
     * cannot cancel and near-identical inputs (consecutive
     * milliseconds, sizes one byte apart) produce unrelated
     * tokens. A raw {@code mtime ^ size} would not: two
     * regions written a second apart with sizes a second's
     * worth of bytes apart could land on the same value.
     *
     * @return an opaque 64-bit token; different means "re-fetch it"
     */
    public static long of(long lastModifiedMillis, long sizeBytes) {
        return mix(mix(lastModifiedMillis) ^ (sizeBytes * 0x9E3779B97F4A7C15L));
    }

    /** SplitMix64's finalizer. A full 64-bit avalanche, where one input bit flips ~half the bits. */
    private static long mix(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
