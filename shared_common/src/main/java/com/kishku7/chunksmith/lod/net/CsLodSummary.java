package com.kishku7.chunksmith.lod.net;

/**
 * The cheap "has anything changed?" question -- a whole region index folded into (count, aggregate).
 *
 * <p>It is what makes the periodic checksum sync affordable. The sync exists for the player standing
 * STILL, who otherwise sees nothing new until they relog, because the travel refresh only fires on
 * movement. So the client asks for a summary: two numbers, compared against the same two computed over
 * what it holds, and only when they differ does it pay for a full index. Unchanged: 22 bytes out,
 * 34 bytes back.
 *
 * <p>The fold has to be order-independent, since the server enumerates from {@code Files.list} and the
 * client from the last index it was given -- identical sets, different order. Hence XOR rather than a
 * checksum. XOR of RAW hashes would be a bad aggregate, though: equal tokens cancel to zero and swapping
 * two regions' coordinates is invisible. Each region is therefore bound to its OWN COORDINATES and
 * avalanched -- {@code token(x, z, hash)} -- and those are XORed, with the count carried alongside as a
 * second check.
 */
public final class CsLodSummary {

    private CsLodSummary() {
    }

    /** A folded index: how many regions, and one number standing in for all of their freshness tokens. */
    public record Snapshot(int count, long aggregate) {

        public static final Snapshot EMPTY = new Snapshot(0, 0L);
    }

    /** Fold one region into a running aggregate -- start from 0. */
    public static long fold(final long aggregate, final int regionX, final int regionZ, final long hash) {
        return aggregate ^ token(regionX, regionZ, hash);
    }

    /**
     * The per-region contribution: (x, z, hash) avalanched into 64 bits so every field participates in
     * every output bit. Change any of the three and this number is unrelated to what it was.
     */
    public static long token(final int regionX, final int regionZ, final long hash) {
        final long packed = ((long) regionX << 32) ^ (regionZ & 0xFFFFFFFFL);
        return mix(mix(packed) ^ (hash * 0x9E3779B97F4A7C15L));
    }

    /** SplitMix64's finalizer -- the same avalanche {@link CsLodRegionHash} uses, for the same reason. */
    private static long mix(final long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
