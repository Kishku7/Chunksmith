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
 * Push a CSLOD record into Distant Horizons instead of waiting to be
 * pulled. Drives {@code /cslod dhpush}: the backfill for a world that
 * was pregenerated before DH was ever installed.
 *
 * <p>The world-generator override only ever fires on a level that has
 * a server. DH's {@code WorldGenerationQueue} is built solely by
 * {@code AbstractDhServerLevel}, and a multiplayer client gets a
 * {@code RemoteWorldRetrievalQueue} instead, so {@code
 * generateApiChunk} is never called there. The whole pull design is
 * inapplicable to a client; the client must push. The push lands in
 * {@code DhApi.Delayed.terrainRepo.overwriteChunkDataAsync} -> {@code
 * SharedApi.applyChunkUpdate}, the same path DH uses when a player
 * edits a block: it writes at gen step LIGHT, persists, and
 * re-renders itself.
 *
 * <p>The open question is not the API call but whether a {@link
 * LevelChunk} synthesized from a stored record is LIT correctly: if
 * DH reads light from the level's light engine rather than from the
 * chunk we hand it, the chunk comes out BLACK and nothing reports an
 * error. So: run it, then LOOK at it. A {@code DhApiResult.success}
 * means QUEUED, not WRITTEN, so the counters below cannot prove
 * retention either. Count rows in DH's SQLite.
 *
 * <p>Known gate, and the reason this may report success and do
 * nothing on a real server: {@code
 * DhClientLevel.shouldProcessChunkUpdate} silently discards an update
 * for any position seen in the last 10 minutes when connected to a DH
 * server with real-time updates on, while still returning {@code
 * createSuccess()}. Singleplayer, the only place this class runs, is
 * not affected, so Chunksmith uses DH's public API only and never
 * mixins into DH.
 *
 * <p>Version-blind: the only Minecraft symbols are {@code
 * LevelChunk(Level, ChunkPos)} and {@code getSections()}, both stable
 * 1.20.1 -&gt; 26. All the drift is inside {@link
 * CsLodSectionBuilder}.
 */
public final class CsLodDhPusher {

    private CsLodDhPusher() {
    }

    public static int push(final ServerLevel level,
                           final IDhApiLevelWrapper wrapper,
                           final Path storeRoot,
                           final Consumer<String> progress) throws IOException {
        if (CsLodDhSupport.isDisabled()) {
            progress.accept("Distant Horizons was ruled out earlier this session; not pushing");
            return 0;
        }

        int[] pushed = {0};
        int[] failed = {0};

        // LinkageError, not Exception: overwriteChunkDataAsync is our only call into DH's terrain repo, so
        // a mismatched DH blows up here as an Error (NoSuchMethodError / NoClassDefFoundError /
        // AbstractMethodError), which catch (Exception) does not catch. The wide DH range we claim rests
        // on this signature having been stable since DH 2.0.0-a; the catch makes being wrong containable.
        try {
            CsLodRegionStore.forEachChunk(storeRoot, record -> {
                LevelChunk chunk = synthesize(level, record);
                DhApiResult<Void> result =
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
        } catch (LinkageError e) {
            CsLodDhSupport.disable(e);
            progress.accept("stopped after " + pushed[0] + " chunks: this Distant Horizons does not have"
                    + " the API Chunksmith was built against (" + CsLodDhSupport.version() + ")."
                    + " DH is disabled for this session; nothing else is affected.");
            return pushed[0];
        }

        progress.accept("done: pushed " + pushed[0] + ", failed " + failed[0]
                + ". NOTE a 'success' here does NOT prove DH kept it; check the DB and LOOK at the terrain");
        return pushed[0];
    }

    /**
     * The empty {@code LevelChunk(Level, ChunkPos)} constructor
     * allocates the section array for the level's height; we fill it
     * with sections rebuilt from the record, the same reconstruction
     * the voxy injector does and that P2 proved correct.
     */
    private static LevelChunk synthesize(ServerLevel level, CsLodChunk record) {
        LevelChunk chunk = new LevelChunk(level, new ChunkPos(record.getChunkX(), record.getChunkZ()));
        LevelChunkSection[] sections = chunk.getSections();
        int count = Math.min(sections.length, record.getSections().size());
        for (int i = 0; i < count; i++) {
            sections[i] = CsLodSectionBuilder.rebuild(level, record, record.getSections().get(i));
        }
        return chunk;
    }
}
