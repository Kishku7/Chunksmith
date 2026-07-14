package com.kishku7.chunksmith.lod.net;

/**
 * The region freshness token -- derived from (mtime, size), never from the file's CONTENTS.
 *
 * <p><b>This class exists because reading the contents was a server-killing bug.</b> Until 3.1.0-beta-4 the
 * server answered a region index by CRC32-ing every region file in the client's radius:
 * {@code crc.update(Files.readAllBytes(file))}, on the SERVER MAIN THREAD, once per index request. On a
 * 340-region / 1567 MB store that is 366.9 MB read and allocated per request -- and because a CSLOD region
 * is several MB, EVERY ONE of those byte[] is a G1 HUMONGOUS allocation, which goes straight into old gen
 * and is only reclaimed by a concurrent cycle. A client re-asks every 5 seconds while it travels, so a
 * single connected LOD client sustained ~73 MB/s of humongous garbage on the tick thread. Old gen filled,
 * the concurrent cycle could not keep up, the heap pegged at 100%, and the server thrashed until even
 * {@code saveAllChunks} could not allocate -- which is how a "Saving worlds" hung for 67 minutes.
 *
 * <p>The hash never needed the bytes. Its own javadoc said so: it is <b>a cache-freshness check, not a
 * security boundary</b> (the handshake token is the security boundary), and it proposed exactly this fix --
 * "cached per (path, mtime, size)". The only question the client asks of it is <i>"is this the same region I
 * already have?"</i>, and (mtime, size) answers that correctly:
 *
 * <ul>
 *   <li><b>A region that changed produces a different token.</b> The store rewrites a region by appending
 *       records and rewriting header slots, which always moves mtime and almost always moves size. Even an
 *       in-place rewrite of identical length moves mtime.</li>
 *   <li><b>mtime granularity cannot hide a change.</b> {@code FileTime.toMillis()} is millisecond-resolution,
 *       so in principle two writes inside one millisecond could collide. They cannot here: the server
 *       refuses to index a region until it has been UNTOUCHED for {@link CsLodStoreScan#SETTLE_MILLIS}
 *       (10 s), so any region that ever reaches a client has been idle for ten seconds, and the next write
 *       to it lands at least ten seconds later on the clock. A same-millisecond collision is unreachable.</li>
 *   <li><b>It is not a checksum and does not pretend to be.</b> It detects CHANGE, not corruption. Corruption
 *       in transit is HTTP's and TCP's problem, and a region that decodes badly is dropped by the codec's
 *       own bounds checks.</li>
 * </ul>
 *
 * <p><b>What a copied store means.</b> Copy a world between machines (or restore it from a backup, or rsync
 * it without {@code -t}) and every region's mtime changes while its contents do not. Every token therefore
 * changes, and every connected client concludes its cached copy is stale and re-downloads the regions in its
 * radius. That is a REAL cost and it is the honest price of this design: it is bounded (one re-download, over
 * the HTTP backchannel, of the regions in one radius -- ~370 MB for the store above), it happens only when an
 * operator moves the store, and it is safe in the only direction that matters. The failure we refuse to have
 * is the other one: a token that says "unchanged" about a region that changed, which leaves a player looking
 * at terrain that no longer exists with no way to ever correct it. Fresh-but-redundant is a bandwidth bill;
 * stale-but-trusted is a bug you cannot see.
 *
 * <p><b>The client never recomputes this.</b> It cannot -- the mtime of the client's own copy is when the
 * CLIENT wrote it, which has nothing to do with the server's. So the token is OPAQUE to the client: it stores
 * whatever number the server sent alongside the region it received (see {@code CsLodManifest}) and compares
 * that recorded number against the next index. This is why the client's cache check no longer reads its own
 * region files either -- the same 340-file, 1.5 GB read the server was doing, which the client was ALSO doing
 * on every single index.
 */
public final class CsLodRegionHash {

    private CsLodRegionHash() {
    }

    /**
     * The token for a region file with this last-modified time and this length.
     *
     * <p>Both inputs are folded through a 64-bit avalanche (the SplitMix64 finalizer) so that the two fields
     * cannot cancel and so that near-identical inputs -- consecutive milliseconds, sizes one byte apart --
     * produce completely unrelated tokens. A raw {@code mtime ^ size} would not: two regions written a
     * second apart with sizes a second's worth of bytes apart could land on the same value.
     *
     * @param lastModifiedMillis the file's mtime, in milliseconds since the epoch
     * @param sizeBytes          the file's length in bytes
     * @return an opaque 64-bit freshness token; equal tokens mean "the same region content, as far as this
     *         server can tell", different tokens mean "re-fetch it"
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
