/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) 2025-2026 Kishku7
 *
 * Chunksmith is a fork of Chunky (https://github.com/pop4959/Chunky)
 * by pop4959 and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.kishku7.chunksmith.lod;

import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * The {@code /cslod inject} backfill: replays a CSLOD store into voxy.
 *
 * <p>Injection goes through {@link VoxelIngestService#rawIngest}, not {@code
 * tryAutoIngestChunk}: rawIngest takes the section and its light directly, so we hand
 * voxy the real light captured at generation time. rawIngest has no light gate at all,
 * which is precisely why the light we stored has to be right; a mistake here yields
 * silently black LODs rather than an error.
 *
 * <p>Throttled on voxy's own queue: its ingest deque is unbounded and never reports
 * saturation, so a backfill that just hammered it would OOM. We watch {@code
 * getTaskCount()} and wait.
 *
 * <p>Generated only where a voxy jar exists to compile against: Fabric 1.21.11 and
 * Fabric 26.x. See {@link VoxyLodSink}.
 */
public final class CsLodVoxyInjector {

    private static final int VOXY_QUEUE_LIMIT = 512;

    private static final String CAUSE_INCOMPATIBLE = "voxy-incompatible";

    private CsLodVoxyInjector() {
    }

    /**
     * Returns true when there is a voxy engine to inject into (i.e. singleplayer / a client instance).
     *
     * @return true on a client instance with a live voxy engine
     */
    public static boolean voxyAvailable() {
        // Ask the loader first. voxy is a client-side mod, so the overwhelmingly common case (every
        // dedicated server there is) is that it is simply not installed, and then the VoxyCommon
        // reference below is an unresolvable class: a NoClassDefFoundError, a LinkageError, which the catch
        // beneath would dutifully report as "voxy is installed, but this build of it does not match ...
        // please report it". Past this gate a LinkageError means what the catch says it means: voxy IS
        // here, and it is not the voxy we compiled against.
        if (!LodPlatform.isModLoaded("voxy")) {
            return false;
        }
        try {
            return VoxyCommon.getInstance() != null;
        } catch (LinkageError error) {
            // voxy is installed but we cannot reach its engine. A bare false here used to make
            // `/cslod inject` say "voxy is not running", a lie that sends the player at the wrong problem.
            warnIncompatible(error);
            return false;
        }
    }

    /**
     * Announces, once, that the installed voxy does not match the one we compiled
     * against. A {@link LinkageError} out of a voxy call is not a transient condition.
     * The jar that is loaded does not contain the member we compiled against, which is
     * what a drifting fork looks like from the inside.
     */
    private static void warnIncompatible(LinkageError error) {
        LodWarnings.once(CAUSE_INCOMPATIBLE,
                "voxy is installed, but this build of it does not match the voxy Chunksmith was built"
                        + " against (" + error + "). Chunksmith cannot feed LODs into it. This normally"
                        + " means a voxy fork that changed a method or a field. Please report it, with your"
                        + " voxy version.");
    }

    /**
     * Replays the whole store for one dimension into voxy. Runs on the calling thread.
     * Callers must hand it a background thread, not the server thread.
     *
     * @return the number of chunks replayed
     */
    public static int inject(ServerLevel level, Path storeRoot, Consumer<String> progress)
            throws IOException {
        WorldIdentifier world = WorldIdentifier.of(level);

        int[] chunks = {0};
        int[] sections = {0};

        int visited;
        try {
            visited = CsLodRegionStore.forEachChunk(storeRoot, record -> {
                awaitVoxyCapacity();
                sections[0] += injectChunk(level, world, record);
                chunks[0]++;
                if (chunks[0] % 500 == 0) {
                    progress.accept("injected " + chunks[0] + " chunks (" + sections[0] + " sections)");
                }
            });
        } catch (LinkageError error) {
            // rawIngest is our first and only call into voxy's ingest path, so a fork with a different
            // signature surfaces here as an Error, past the command's catch(Exception), in total silence.
            warnIncompatible(error);
            progress.accept("ABORTED after " + chunks[0] + " chunks: this voxy will not accept our data ("
                    + error + ")");
            return chunks[0];
        }

        progress.accept("done: " + chunks[0] + " chunks, " + sections[0] + " sections injected into voxy"
                + (visited == chunks[0] ? "" : " (" + visited + " visited)"));
        return chunks[0];
    }

    private static int injectChunk(final ServerLevel level,
                                   final WorldIdentifier world,
                                   final CsLodChunk record) {
        int injected = 0;
        List<CsLodChunk.Section> sections = record.getSections();
        for (int i = 0; i < sections.size(); i++) {
            CsLodChunk.Section section = sections.get(i);
            // The reconstruction (and every MC-version drift in it) lives in one place.
            LevelChunkSection rebuilt = CsLodSectionBuilder.rebuild(level, record, section);
            DataLayer sky = light(section.getSkyLight(), section.getUniformSky());
            DataLayer block = light(section.getBlockLight(), section.getUniformBlockLight());

            VoxelIngestService.rawIngest(world, rebuilt,
                    record.getChunkX(), record.getMinSectionY() + i, record.getChunkZ(),
                    block, sky);
            injected++;
        }
        return injected;
    }

    private static DataLayer light(byte[] packed, int uniform) {
        if (packed != null) {
            return new DataLayer(packed.clone());
        }
        return uniform > 0 ? new DataLayer(uniform) : new DataLayer();
    }

    private static void awaitVoxyCapacity() {
        try {
            while (VoxyCommon.getInstance() != null
                    && VoxyCommon.getInstance().getIngestService().getTaskCount() > VOXY_QUEUE_LIMIT) {
                Thread.sleep(20L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
