package com.kishku7.chunksmith.lod.net;

/**
 * The region freshness token -- derived from (mtime, size), never from the file's CONTENTS.
 *
 * <p><b>This class exists because reading the contents was a server-killing bug.</b> Until 3.1.0-beta-4 the
 * server answered every index request with {@code crc.update(Files.readAllBytes(file))} over every region in
 * the client's radius, on the SERVER MAIN THREAD: 366.9 MB read per request on a 340-region / 1567 MB store,
 * each multi-MB byte[] a G1 HUMONGOUS allocation straight into old gen. A client re-asks every 5 seconds
 * while it travels, so ONE connected LOD client sustained ~73 MB/s of humongous garbage on the tick thread;
 * the heap pegged at 100% and even {@code saveAllChunks} could not allocate, which is how a "Saving worlds"
 * hung for 67 minutes.
 *
 * <p>It is a cache-freshness check, not a security boundary (the handshake token is that): the only question
 * is "is this the same region I already have?". Any rewrite moves mtime -- the store appends records and
 * rewrites header slots, and even an in-place rewrite of identical length moves it -- and a same-millisecond
 * collision is unreachable because the server will not index a region until it has been UNTOUCHED for
 * {@link CsLodStoreScan#SETTLE_MILLIS} (10 s). It detects CHANGE, not corruption: bad bytes in transit are
 * HTTP's and TCP's problem, and a region that decodes badly is dropped by the codec's own bounds checks.
 *
 * <p><b>What a copied store means.</b> Copy or rsync a world without {@code -t} and every region's mtime
 * changes while its contents do not, so every client re-downloads the regions in its radius (~370 MB for the
 * store above). That is the honest price of erring the only safe way: the failure we refuse is a token that
 * says "unchanged" about a region that changed, leaving a player on terrain that no longer exists.
 *
 * <p><b>The client never recomputes this.</b> It cannot -- the mtime of its own copy is when the CLIENT
 * wrote it. The token is OPAQUE to it: it stores the number the server sent with the region (see
 * {@code CsLodManifest}) and compares that against the next index, rather than reading its own files.
 */
public final class CsLodRegionHash {

    private CsLodRegionHash() {
    }

    /**
     * The token for a region file with this mtime and length. Both inputs are folded through a 64-bit
     * avalanche (the SplitMix64 finalizer) so the two fields cannot cancel and near-identical inputs --
     * consecutive milliseconds, sizes one byte apart -- produce unrelated tokens. A raw
     * {@code mtime ^ size} would not: two regions written a second apart with sizes a second's worth of
     * bytes apart could land on the same value.
     *
     * @return an opaque 64-bit token; equal means "the same region content, as far as this server can
     *         tell", different means "re-fetch it"
     */
    public static long of(final long lastModifiedMillis, final long sizeBytes) {
        return mix(mix(lastModifiedMillis) ^ (sizeBytes * 0x9E3779B97F4A7C15L));
    }

    /** SplitMix64's finalizer. A full 64-bit avalanche: one input bit flips ~half the output bits. */
    private static long mix(final long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
