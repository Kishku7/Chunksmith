package com.kishku7.chunksmith.lod.client.render;

import com.kishku7.chunksmith.lod.CsLodChunk;
import com.kishku7.chunksmith.lod.CsLodSectionBuilder;
import com.kishku7.chunksmith.lod.LodWarnings;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Downloaded CSLOD records go into the player's voxy from here. Hard-references voxy, so it is only ever
 * class-loaded once {@code isModLoaded("voxy")} has passed.
 *
 * <p>Uses {@code rawIngest}, not {@code tryAutoIngestChunk}: rawIngest takes the section and its light
 * directly, so voxy gets the real light captured on the server at generation time: the whole point of
 * storing sky and block light separately in CSLOD. <b>rawIngest has NO light gate</b>: hand it wrong light
 * and it will cheerfully produce BLACK LODs and report success.
 *
 * <p>Throttled on voxy's own queue: its ingest deque is unbounded and never reports saturation, so an
 * unthrottled replay of a large store would drive the heap into an OOM. (That is the failure that OOMed
 * Voxy WorldGen V2 badly enough that upstream voxy ships a hard `breaks` against it.)
 *
 * <p>These calls are direct, not reflective. {@code VoxelIngestService.rawIngest},
 * {@code VoxyCommon.getInstance()}, {@code getIngestService().getTaskCount()} and
 * {@code WorldIdentifier.of} have identical signatures in every fork jar the {@code Renderers} roster
 * names, all checked with {@code javap}, so a reflective per-chunk call would cost real time and absorb
 * nothing. The one place fork drift has been observed is voxy's config field, and that is the one place we
 * reflect; see {@link VoxyRadius}. If that stops being true, a {@code LinkageError} out of any of these
 * calls disables the voxy sink for the session and says so once.
 */
public final class VoxyTarget {

    /** Pause while voxy's ingest backlog is above this. */
    private static final int QUEUE_LIMIT = 512;

    private static final String CAUSE_INCOMPATIBLE = "voxy-incompatible";

    /**
     * Set once voxy has proved it cannot accept our data. Not a "retry later" flag: a LinkageError is a
     * permanent, structural mismatch, so retrying per chunk would burn CPU and spam the log for a result
     * that cannot change until the player changes their mods.
     */
    private static volatile boolean broken;

    private VoxyTarget() {
    }

    /**
     * Whether this loader has a voxy adapter at all. True here; false in the NeoForge copy.
     *
     * <p>{@link com.kishku7.chunksmith.lod.client.Renderers#hasVoxy()} is gated on this, so a NeoForge
     * client that somehow has a mod called {@code voxy} is not announced as one we can then feed.
     */
    public static boolean supported() {
        return true;
    }

    /** True when there is a voxy engine to ingest into, and it has not already failed on us. */
    public static boolean available() {
        if (broken) {
            return false;
        }
        try {
            return VoxyCommon.getInstance() != null;
        } catch (LinkageError error) {
            // voxy is installed (its mod id is loaded) but we cannot even ask it for its engine --
            // silence here means the player sees no distant terrain and no reason why.
            disable(error);
            return false;
        }
    }

    /** @return sections ingested; 0 if voxy has been ruled out. */
    public static int inject(Level level, CsLodChunk record) {
        if (broken) {
            return 0;
        }
        try {
            return doInject(level, record);
        } catch (LinkageError error) {
            // The first call into rawIngest is where a fork with a different ingest signature would surface
            // as a NoSuchMethodError, which is an Error and would sail straight past `catch (Exception)`.
            disable(error);
            return 0;
        }
    }

    private static int doInject(Level level, CsLodChunk record) {
        final WorldIdentifier world = WorldIdentifier.of(level);
        int ingested = 0;
        for (int i = 0; i < record.getSections().size(); i++) {
            awaitCapacity();
            final CsLodChunk.Section section = record.getSections().get(i);
            final LevelChunkSection rebuilt = CsLodSectionBuilder.rebuild(level, record, section);
            final DataLayer sky = light(section.getSkyLight(), section.getUniformSky());
            final DataLayer block = light(section.getBlockLight(), section.getUniformBlockLight());

            VoxelIngestService.rawIngest(world, rebuilt,
                    record.getChunkX(), record.getMinSectionY() + i, record.getChunkZ(),
                    block, sky);
            ingested++;
        }
        return ingested;
    }

    /** Rule voxy out for this session and SAY SO once, loudly, in words a player can act on. */
    private static void disable(LinkageError error) {
        broken = true;
        LodWarnings.once(CAUSE_INCOMPATIBLE,
                "voxy is installed, but this build of it does not match the voxy Chunksmith was built"
                        + " against (" + error + "). Chunksmith cannot feed it, so NO distant terrain will"
                        + " appear in voxy; Distant Horizons, if you have it, is unaffected. This usually"
                        + " means a voxy fork that changed a method or a field. Please report it, with your"
                        + " voxy version.");
    }

    /** Rebuild a DataLayer from our packed nibbles, or from a single uniform value. */
    private static DataLayer light(byte[] packed, int uniform) {
        if (packed != null) {
            return new DataLayer(packed.clone());
        }
        return uniform > 0 ? new DataLayer(uniform) : new DataLayer();
    }

    private static void awaitCapacity() {
        try {
            while (VoxyCommon.getInstance() != null
                    && VoxyCommon.getInstance().getIngestService().getTaskCount() > QUEUE_LIMIT) {
                Thread.sleep(20L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // A LinkageError out of getIngestService()/getTaskCount() is deliberately not caught here:
        // inject() catches it one frame up, so it is one disable and one warning, not two.
    }
}
