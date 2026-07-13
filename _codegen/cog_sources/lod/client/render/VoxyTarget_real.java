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
 * Feeds downloaded CSLOD records into the player's voxy.
 *
 * <p>Hard-references voxy, so it is only ever class-loaded once {@code isModLoaded("voxy")} has passed.
 *
 * <p>Uses {@code rawIngest}, not {@code tryAutoIngestChunk}: rawIngest takes the section and its light
 * DIRECTLY, so voxy gets the REAL light that was captured on the server at generation time -- which is the
 * whole point of storing sky and block light separately in CSLOD.
 *
 * <p><b>rawIngest has NO light gate.</b> Hand it wrong light and it will cheerfully produce BLACK LODs and
 * report success. So the light we ship has to be right, and the only way to know is to look at it.
 *
 * <p>Throttled on voxy's own queue: its ingest deque is UNBOUNDED and never reports saturation, so an
 * unthrottled replay of a large store would drive the heap into an OOM. (That is the failure that OOMed
 * Voxy WorldGen V2 badly enough that upstream voxy ships a hard `breaks` against it.)
 *
 * <p><b>Called DIRECTLY, not reflectively -- on purpose.</b> Every fork of voxy we could reach was checked
 * with {@code javap} (2026-07-13): {@code VoxelIngestService.rawIngest}, {@code VoxyCommon.getInstance()},
 * {@code getIngestService().getTaskCount()} and {@code WorldIdentifier.of} have identical signatures in all
 * of them. Zero drift, so there is nothing for reflection to absorb here, and a reflective call on the
 * per-chunk path would cost real time for no benefit. The one place fork drift HAS been observed is voxy's
 * config field, and that is the one place we reflect -- see {@link VoxyRadius}.
 *
 * <p><b>But if that ever stops being true, the player is TOLD.</b> A {@code LinkageError} out of any of
 * these calls means the installed voxy is not the voxy we compiled against. We used to swallow it and hand
 * back "0 sections ingested", which looks exactly like success: the download worked, the log said done, and
 * the horizon stayed empty. Now the first such failure disables the voxy sink for the session and says so,
 * once, in plain words.
 */
public final class VoxyTarget {

    /** Pause while voxy's ingest backlog is above this. */
    private static final int QUEUE_LIMIT = 512;

    /** Warn key: voxy is here, but it will not take our data. */
    private static final String CAUSE_INCOMPATIBLE = "voxy-incompatible";

    /**
     * Set once voxy has proved it cannot accept our data. Not a "retry later" flag -- a LinkageError is a
     * permanent, structural mismatch (the method or field is not in the jar that is loaded), so retrying it
     * per chunk would just burn CPU and spam the log for a result that cannot change until the player
     * changes their mods.
     */
    private static volatile boolean broken;

    private VoxyTarget() {
    }

    /**
     * Whether THIS loader has a voxy adapter at all. True here; false in the NeoForge copy.
     *
     * <p>{@link com.kishku7.chunksmith.lod.client.Renderers#hasVoxy()} is gated on this, so a NeoForge client
     * that somehow has a mod called {@code voxy} does not get announced to the server as a voxy client that
     * we then cannot feed. See the NeoForge seam copy of this class for why it does not exist there.
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
        } catch (final LinkageError error) {
            // voxy is INSTALLED (its mod id is loaded) but we cannot even ask it for its engine. Silence
            // here means the player sees no distant terrain and no reason why -- the exact failure this
            // whole warning path exists to kill.
            disable(error);
            return false;
        }
    }

    /**
     * Inject one chunk record.
     *
     * @return number of sections ingested; 0 if voxy has been ruled out
     */
    public static int inject(final Level level, final CsLodChunk record) {
        if (broken) {
            return 0;
        }
        try {
            return doInject(level, record);
        } catch (final LinkageError error) {
            // The FIRST call into rawIngest is where a fork with a different ingest signature would surface
            // -- as a NoSuchMethodError, which is an Error and would sail straight past `catch (Exception)`.
            disable(error);
            return 0;
        }
    }

    private static int doInject(final Level level, final CsLodChunk record) {
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

    /** Rule voxy out for this session, and SAY SO -- once, loudly, in words a player can act on. */
    private static void disable(final LinkageError error) {
        broken = true;
        LodWarnings.once(CAUSE_INCOMPATIBLE,
                "voxy is installed, but this build of it does not match the voxy Chunksmith was built"
                        + " against (" + error + "). Chunksmith cannot feed it, so NO distant terrain will"
                        + " appear in voxy -- Distant Horizons, if you have it, is unaffected. This usually"
                        + " means a voxy fork that changed a method or a field. Please report it, with your"
                        + " voxy version.");
    }

    /** Rebuild a DataLayer from our packed nibbles, or from a single uniform value. */
    private static DataLayer light(final byte[] packed, final int uniform) {
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
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // A LinkageError out of getIngestService()/getTaskCount() is NOT caught here on purpose: it belongs
        // to the same "this voxy is not our voxy" cause as rawIngest, and inject() catches it one frame up
        // -- one disable, one warning, not two.
    }
}
