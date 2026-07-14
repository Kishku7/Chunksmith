package com.kishku7.chunksmith.lod.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which regions have already been handed to a renderer THIS SESSION -- keyed by DIMENSION as well as by
 * region coordinates, and remembering WHICH VERSION of each one we drew.
 *
 * <p>The client keeps pulling as the player travels, and every pull returns the whole set of regions within
 * the renderer's radius -- most of which are already drawn. Injecting those again would re-decode and
 * re-push terrain the renderer already has: with voxy that is hundreds of thousands of sections re-ingested
 * to draw nothing new. So a region is injected once, and not again -- <b>unless it has actually changed</b>.
 *
 * <p><b>The dimension is part of the key, and that is one of the two reasons this class exists.</b> It used
 * to be a {@code Set<Long>} of packed region x/z and nothing else -- and region (0,0) is a DIFFERENT PLACE
 * in every dimension. Once the overworld's region (0,0) had been injected, the Nether's region (0,0) was
 * considered "already done" and was silently skipped forever: the player walked into the Nether and its LODs
 * never appeared, while every counter and every log line reported success. Two dimensions, one keyspace, no
 * collision detection. Region coordinates are only meaningful WITH the dimension they belong to -- so they
 * are never a key without it.
 *
 * <p><b>The freshness token is part of the VALUE, and that is the other reason.</b> Keying on
 * (dimension, x, z) alone answers "have I ever drawn this region?", but the question the injector actually
 * needs answered is "have I drawn THIS VERSION of this region?". Those came apart the moment the periodic
 * sync landed in 3.1.0-beta-4. A pregen does not only create NEW regions -- it keeps GROWING the ones the
 * player is standing on, for hours. The sync notices (the token moved), the cache notices (the token moved),
 * the downloader dutifully re-fetches the bigger file... and then the injector looked up (dimension, x, z),
 * found it, and threw the new data away. Silently, and with a success message. The player would have seen
 * the far ring of new regions appear and the terrain under their feet stay frozen at whatever it was when
 * they joined -- which is a stranger and harder-to-report bug than getting nothing at all.
 *
 * <p>So a claim now carries the token the server advertised, and it succeeds when we have never drawn this
 * (dimension, region) OR when the token differs from the one we last drew. Re-injecting a genuinely-changed
 * region is exactly what both renderers want: DH overwrites by chunk position, and voxy re-ingests the
 * sections. Re-injecting an UNCHANGED one is the waste this class exists to prevent, and it still cannot
 * happen.
 *
 * <p>Deliberately MC-free so it can be unit-tested. Thread-safe: the injector runs off the game thread and
 * the network handler releases regions from another.
 */
public final class InjectedRegions {

    /** (dimension, x, z) -> the freshness token of the version we last handed to a renderer. */
    private final Map<String, Long> injected = new ConcurrentHashMap<>();

    /**
     * Claim a region for injection.
     *
     * <p>Succeeds if we have never injected this (dimension, region), or if the version we injected is not
     * the one being offered now. Atomic against a concurrent claim of the same region: exactly one caller
     * wins.
     *
     * @param hash the freshness token the server advertised for this region, from the region index
     * @return true if this version of this (dimension, region) has NOT been injected -- the caller now owns
     *         it and must either inject it or {@link #release} it
     */
    public boolean claim(final String dimension, final int regionX, final int regionZ, final long hash) {
        final String key = key(dimension, regionX, regionZ);
        final Long previous = this.injected.put(key, hash);
        if (previous == null) {
            // Never seen. Ours.
            return true;
        }
        if (previous == hash) {
            // Already drawn, and it has not moved on. Put back exactly what was there and decline.
            return false;
        }
        // Drawn, but the server has a different version now. Ours -- and `put` has already staked it.
        return true;
    }

    /**
     * Give a claimed region back, so a later refresh retries it rather than skipping it forever.
     *
     * <p>Used when the region could not be read, or when no renderer was ready in time, or when the player
     * left the dimension before we got to it.
     *
     * <p><b>Releasing FORGETS the region entirely</b>, rather than restoring the token that was there
     * before. That is deliberate and it is the safe direction: the caller is telling us the injection did
     * not happen, so the honest state is "we do not know what this renderer has", and the next index will
     * re-claim and re-inject it. Restoring a previous token would mean an interrupted upgrade of a region
     * could leave us believing we had drawn a version we had not.
     */
    public void release(final String dimension, final int regionX, final int regionZ) {
        this.injected.remove(key(dimension, regionX, regionZ));
    }

    /** Has this exact (dimension, region) been injected, in ANY version? */
    public boolean contains(final String dimension, final int regionX, final int regionZ) {
        return this.injected.containsKey(key(dimension, regionX, regionZ));
    }

    /**
     * The token of the version of this (dimension, region) we last injected, or null if we never have.
     * Exists so a test can assert WHICH version we believe we drew, not merely that we drew something.
     */
    public Long injectedHash(final String dimension, final int regionX, final int regionZ) {
        return this.injected.get(key(dimension, regionX, regionZ));
    }

    /** How many (dimension, region) pairs are held. */
    public int size() {
        return this.injected.size();
    }

    /** Forget everything. Called on disconnect -- the store is keyed by server, and so is this. */
    public void clear() {
        this.injected.clear();
    }

    /**
     * The key. A separator that cannot occur in a dimension id (which is validated against
     * {@code [a-z0-9_.-]} by {@link CsLodStore}) keeps "a" + "1,2" from ever colliding with "a1" + ",2".
     */
    static String key(final String dimension, final int regionX, final int regionZ) {
        return dimension + '/' + regionX + ',' + regionZ;
    }
}
