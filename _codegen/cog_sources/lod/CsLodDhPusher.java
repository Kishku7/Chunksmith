package com.kishku7.chunksmith.lod;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * PUSH a CSLOD record into Distant Horizons, instead of waiting to be pulled. Drives {@code /cslod
 * dhpush}: the backfill for a world that was pregenerated before DH was ever installed.
 *
 * <p><b>Why this exists.</b> The world-generator override only ever fires on a level that has a
 * server: DH's {@code WorldGenerationQueue} is built solely by {@code AbstractDhServerLevel}, and a
 * MULTIPLAYER CLIENT gets a {@code RemoteWorldRetrievalQueue} instead -- so {@code generateApiChunk} is
 * never called there. The whole pull design is inapplicable to Chunksmith-Client. The client must PUSH.
 *
 * <p>The push path is {@code DhApi.Delayed.terrainRepo.overwriteChunkDataAsync(levelWrapper, {chunk, level})}
 * -> {@code SharedApi.applyChunkUpdate}: the same code path DH uses when a player edits a block. It
 * writes at gen step LIGHT, persists, and re-renders on its own.
 *
 * <p><b>What this spike is really testing</b> is not the API call -- it is whether we can SYNTHESIZE a
 * vanilla {@link LevelChunk} from a stored CSLOD record that DH will accept and, crucially, LIGHT
 * correctly. If DH reads light from the level's light engine rather than from the chunk we hand it, a
 * synthesized chunk will come out BLACK, and (as everywhere else in this project) nothing will report an
 * error. So: run it, then LOOK at it.
 *
 * <p>Known gate, and the reason this may report success and do nothing on a real server:
 * {@code DhClientLevel.shouldProcessChunkUpdate} silently DISCARDS an update for any position seen in the
 * last 10 minutes when connected to a DH server with real-time updates on -- while still returning
 * {@code createSuccess()}. Chunksmith-Client mixins that gate off. Singleplayer -- which is the only place
 * this class runs -- is not affected, so Chunksmith itself never needs to touch DH's internals: PUBLIC API
 * only, no mixin into DH from this mod.
 *
 * <p><b>A {@code DhApiResult.success} means QUEUED, not WRITTEN.</b> The counters below cannot prove
 * retention. Verify it by counting rows in DH's SQLite, and by LOOKING at the terrain.
 *
 * <p>Version-blind: the only Minecraft symbols are {@code LevelChunk(Level, ChunkPos)} and
 * {@code getSections()}, both stable 1.20.1 -&gt; 26. All the drift is inside {@link CsLodSectionBuilder}.
 *
 * <p>SHARED SOURCE -- canonical location: _codegen/cog_sources/lod. Edit ONLY there; the per-cell copy
 * under gen/ is overwritten by cog-gen on every build.
 */
public final class CsLodDhPusher {

    private CsLodDhPusher() {
    }

    /**
     * Replay a CSLOD store into DH by pushing synthesized chunks at it.
     *
     * @return number of chunks pushed
     */
    public static int push(final ServerLevel level,
                           final IDhApiLevelWrapper wrapper,
                           final Path storeRoot,
                           final Consumer<String> progress) throws IOException {
        if (CsLodDhSupport.isDisabled()) {
            progress.accept("Distant Horizons was ruled out earlier this session; not pushing");
            return 0;
        }

        final int[] pushed = {0};
        final int[] failed = {0};

        // LinkageError, not Exception. overwriteChunkDataAsync is our FIRST and only call into DH's
        // terrain repo, so it is where a DH that does not match the API we compiled against actually
        // blows up -- and it blows up as an Error (NoSuchMethodError / NoClassDefFoundError /
        // AbstractMethodError), which `catch (Exception)` does NOT catch. Chunksmith claims a wide DH
        // range on the evidence that this signature has been stable since DH 2.0.0-a; this catch is what
        // makes being WRONG about that a logged, contained degradation instead of a dead server thread.
        try {
            CsLodRegionStore.forEachChunk(storeRoot, record -> {
                final LevelChunk chunk = synthesize(level, record);
                final DhApiResult<Void> result =
                        DhApi.Delayed.terrainRepo.overwriteChunkDataAsync(wrapper, new Object[]{chunk, level});
                if (result.success) {
                    pushed[0]++;
                } else {
                    failed[0]++;
                    if (failed[0] == 1) {
                        progress.accept("first failure: " + result.message);
                    }
                }
                if ((pushed[0] + failed[0]) % 250 == 0) {
                    progress.accept("pushed " + pushed[0] + ", failed " + failed[0]);
                }
            });
        } catch (final LinkageError e) {
            CsLodDhSupport.disable(e);
            progress.accept("stopped after " + pushed[0] + " chunks -- this Distant Horizons does not have"
                    + " the API Chunksmith was built against (" + CsLodDhSupport.version() + ")."
                    + " DH is disabled for this session; nothing else is affected.");
            return pushed[0];
        }

        progress.accept("done: pushed " + pushed[0] + ", failed " + failed[0]
                + " -- NOTE a 'success' here does NOT prove DH kept it; check the DB and LOOK at the terrain");
        return pushed[0];
    }

    /**
     * Build a vanilla {@link LevelChunk} out of a CSLOD record.
     *
     * <p>The empty {@code LevelChunk(Level, ChunkPos)} constructor allocates the section array for the
     * level's height; we fill it with sections rebuilt from the record (the same reconstruction the voxy
     * injector already does and that P2 proved correct).
     */
    private static LevelChunk synthesize(final ServerLevel level, final CsLodChunk record) {
        final LevelChunk chunk = new LevelChunk(level, new ChunkPos(record.getChunkX(), record.getChunkZ()));
        final LevelChunkSection[] sections = chunk.getSections();
        final int count = Math.min(sections.length, record.getSections().size());
        for (int i = 0; i < count; i++) {
            sections[i] = CsLodSectionBuilder.rebuild(level, record, record.getSections().get(i));
        }
        return chunk;
    }
}
