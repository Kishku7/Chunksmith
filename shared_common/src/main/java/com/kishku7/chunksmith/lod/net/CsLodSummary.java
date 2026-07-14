package com.kishku7.chunksmith.lod.net;

/**
 * The cheap "has anything changed?" question -- a whole region index folded into (count, aggregate).
 *
 * <p>This is what makes the periodic checksum sync affordable. The sync's whole reason to exist is the
 * player who is <b>standing still</b>: the travel refresh only fires when they move half a region, and the
 * store-available notice only fires once, so a player who parks at spawn while the operator's pregen runs for
 * six hours currently receives whatever had settled at the moment they were told, and nothing after it. They
 * must relog to see the rest. The fix is to ask, periodically -- but "ask" must not mean "send me the whole
 * index", or the poll becomes the very thing that killed the server.
 *
 * <p>So the client asks for a SUMMARY: two numbers. It compares them against the same two numbers computed
 * over what it actually holds, and only when they differ does it pay for a full index. Nothing changed ->
 * 22 bytes out, 34 bytes back, no index, no fetch, no allocation worth naming.
 *
 * <p><b>The fold is order-independent, and it has to be.</b> The server builds its set from
 * {@code Files.list}, whose order is whatever the filesystem feels like; the client builds its set from the
 * entries of the last index it was given. Those two sets can be identical and still be enumerated in
 * different orders, so the aggregate must not depend on order -- hence XOR, not a running checksum.
 *
 * <p>XOR of raw hashes would be a bad aggregate: two regions with equal tokens would cancel to zero, and
 * swapping a region's coordinates with another's would be invisible. So each region is first bound to its
 * OWN COORDINATES and avalanched -- {@code token(x, z, hash)} -- and it is those that are XORed. A region
 * added, removed, moved, or changed all flip the aggregate. Two independent changes could in principle
 * cancel; that is a 2^-64 coincidence on a cache-freshness check whose worst outcome is one late refresh,
 * and the count is carried alongside precisely so the commonest case (a region appeared) cannot rely on the
 * aggregate alone.
 *
 * <p>MC-agnostic, and pure: unit-testable without a game, a server or a filesystem.
 */
public final class CsLodSummary {

    private CsLodSummary() {
    }

    /** A folded index: how many regions, and one number standing in for all of their freshness tokens. */
    public record Snapshot(int count, long aggregate) {

        /** The empty set -- no regions at all. */
        public static final Snapshot EMPTY = new Snapshot(0, 0L);
    }

    /**
     * Fold one region into a running aggregate.
     *
     * @param aggregate the running value (start from 0)
     * @param regionX   the region's x coordinate -- part of the token, so a region cannot be confused with
     *                  its neighbour
     * @param regionZ   the region's z coordinate
     * @param hash      the region's freshness token (see {@link CsLodRegionHash})
     * @return the new running value
     */
    public static long fold(final long aggregate, final int regionX, final int regionZ, final long hash) {
        return aggregate ^ token(regionX, regionZ, hash);
    }

    /**
     * The per-region contribution: (x, z, hash) avalanched into 64 bits.
     *
     * <p>Coordinates are packed into one long and mixed with the freshness token so that every field
     * participates in every output bit. Change any of the three and this number is unrelated to what it was.
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
