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

package com.kishku7.chunksmith.worldenter;

import com.kishku7.chunksmith.ChunksmithProvider;
import com.kishku7.chunksmith.platform.Config;
//[[[cog
// import cog, compat
// if compat.has_world_clock(mcver):
//     cog.outl("import net.minecraft.core.Holder;")
//]]]
import net.minecraft.core.Holder;
//[[[end]]]
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
//[[[cog
// import cog, compat
// if compat.has_world_clock(mcver):
//     cog.outl("import net.minecraft.world.clock.WorldClock;")
//]]]
import net.minecraft.world.clock.WorldClock;
//[[[end]]]
//[[[cog
// import cog, compat
// cog.outl(compat.gamerules_import(mcver))
//]]]
import net.minecraft.world.level.gamerules.GameRules;
//[[[end]]]
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pre-generates a single-player world on entry, before handing the player control.
 *
 * <p>The shape, from mod_support #20: enter the world, freeze it, pregenerate out
 * to a radius behind a progress screen, then play -- or press the button and play
 * immediately while it keeps working behind you.
 *
 * <p><b>Freezing does not stop generation.</b> That is the fact the whole feature
 * rests on, and it was measured rather than assumed: with the tick frozen and
 * {@code gametime} pinned, a task started from zero still generated. What a
 * freeze stops is chunks being <i>ticked</i> -- mobs, random ticks, weather --
 * because {@code ServerChunkCache.tick} gates only {@code tickChunks()} on
 * {@code runsNormally()}, while the ticket updates and the chunk load/generate
 * pump run either way.
 *
 * <p><b>It is not faster, and nothing here should imply it is.</b> Frozen runs at
 * roughly 60-70% of normal throughput -- the tick that drives chunk promotion is
 * the same tick being frozen. The reporter expected a speed-up and there is not
 * one. The value is that the work happens before you play instead of stuttering
 * underneath you.
 *
 * <p><b>Everything borrowed is written down before it is taken.</b> See {@link
 * WorldEnterState}. The player may quit mid-freeze, and a world left frozen with
 * our gamerule values baked in would be a bug they could not diagnose.
 *
 * <p>Single-player only. It runs off the integrated server, in the client's own
 * JVM, so the screen reads this class directly -- no packets, no protocol.
 */
public final class WorldEnterPregen {

    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");

    /** Pattern for the generated selection. Concentric finishes near the player first. */
    private static final String PATTERN = "concentric";
    private static final String SHAPE = "circle";

    private static final AtomicBoolean ACTIVE = new AtomicBoolean(false);
    private static final PregenEta ETA = new PregenEta();

    private static volatile MinecraftServer server;
    private static volatile String worldKey;
    private static volatile long chunksTotal;
    private static volatile boolean frozenByUs;
    private static volatile long chunksDone;
    private static volatile float percentComplete;
    private static volatile long radiusBlocks;

    private WorldEnterPregen() {
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Called once the integrated server is up.
     *
     * <p>Two jobs, in this order and not the other: <b>first</b> hand back
     * anything a previous session borrowed and never returned, <b>then</b>
     * consider borrowing again. Doing it the other way round would overwrite the
     * record of what the player's settings used to be with our own values, and
     * they would never get them back.
     *
     * @param integrated true only when this is a single-player integrated server
     */
    public static void onServerStarted(MinecraftServer mcServer, boolean integrated) {
        restoreAbandoned(mcServer);

        if (!integrated) {
            return;   // a dedicated server has no world-entry moment and nobody to show a screen to
        }
        Config config = ChunksmithProvider.isLoaded()
                ? ChunksmithProvider.get().getConfig() : null;
        if (config == null || !config.isWorldEnterPregenEnabled()) {
            return;
        }
        ServerLevel level = mcServer.overworld();
        String dimension = dimensionId(level);
        long radius = config.getWorldEnterPregenRadius();

        // ONCE IT IS DONE, IT IS DONE (decided 2026-09-02). Without this the pregen fired on EVERY
        // single-player load: the task skips chunks that already exist, so nothing was destroyed,
        // but the player was put back behind the progress screen on a world they had already
        // waited out. Continuing a PARTIAL run is still wanted -- pressing "Enter World Now" leaves
        // no record, so the next load picks up where it stopped. Only a real completion writes one.
        Optional<WorldEnterDone> alreadyDone = WorldEnterDone.read(worldDir(mcServer));
        if (alreadyDone.isPresent() && alreadyDone.get().satisfies(dimension, radius)) {
            LOGGER.info("Chunksmith: this world has already been pre-generated to {} blocks, so the"
                    + " world-enter pregen is being skipped. Raise worldEnterPregenRadius if you"
                    + " want more, or delete {} in the world folder to run it again.",
                    alreadyDone.get().radiusBlocks(), WorldEnterDone.FILE_NAME);
            return;
        }

        if (!ACTIVE.compareAndSet(false, true)) {
            return;
        }

        server = mcServer;
        worldKey = dimension;
        radiusBlocks = radius;
        chunksTotal = estimateChunks(radius);
        ETA.reset();
        chunksDone = 0L;
        percentComplete = 0.0f;
        listenForProgress();

        // Write the borrow record BEFORE touching anything. If it cannot be written we do not
        // proceed: changing settings we have no way to restore is worse than not running at all.
        // Read through the seam, into a local, rather than inline in the constructor call: the
        // gamerule moved package AND changed accessor shape at 1.21.11, and keeping the drift on one
        // line leaves the record's argument list identical on every version.
        //[[[cog
        // import cog, compat
        // cog.outl("        final boolean timeAdvanceWasOn = %s;" % compat.gamerule_time_get(mcver, "level"))
        //]]]
        final boolean timeAdvanceWasOn = level.getGameRules().get(GameRules.ADVANCE_TIME);
        //[[[end]]]
        WorldEnterState borrowed = new WorldEnterState(
                timeAdvanceWasOn,
                0L,
                config.getThrottleTickBudgetMillis(),
                config.getThrottlePlayerReserveMillis(),
                config.getThrottleTargetMspt());
        if (!borrowed.write(worldDir(mcServer))) {
            LOGGER.warn("Chunksmith: could not record the world-enter state, so the world-enter"
                    + " pregen is being skipped. Nothing has been changed.");
            ACTIVE.set(false);
            return;
        }

        stopTimeAdvancing(mcServer, level);
        raiseThrottleForAnEmptyWorld(config);
        freeze(mcServer, true);

        boolean started = ChunksmithProvider.get().getApi().startTask(
                worldKey, SHAPE, 0.0, 0.0, radius, radius, PATTERN);
        if (!started) {
            LOGGER.warn("Chunksmith: the world-enter pregen could not start a task; releasing the"
                    + " world and restoring settings.");
            release();
            return;
        }
        LOGGER.info("Chunksmith: world-enter pregen started -- radius {} blocks (~{} chunks)."
                + " The world is frozen until it finishes or you choose to enter."
                + " Freezing does NOT make this faster; it stops the world moving while it runs.",
                radius, chunksTotal);
    }

    /**
     * The "Enter World Now" button, and also what completion calls.
     *
     * <p>Generation is deliberately NOT stopped. The player asked to start
     * playing, not to abandon the run -- the throttle's player reserve now
     * applies, so it yields to them from here on.
     */
    public static void release() {
        MinecraftServer mcServer = server;
        if (mcServer == null || !ACTIVE.get()) {
            return;
        }
        restoreFrom(mcServer, WorldEnterState.read(worldDir(mcServer)).orElse(null));
        WorldEnterState.clear(worldDir(mcServer));
        ACTIVE.set(false);
        LOGGER.info("Chunksmith: world released. Any remaining pre-generation continues in the"
                + " background and now yields to you.");
    }

    /**
     * Subscribes to the task's own progress. Deliberately NOT a poll: ChunksmithAPI exposes no
     * "chunks done" getter, and the event already carries the authoritative count the task itself
     * is working from -- which is a better number than anything the screen could reassemble.
     *
     * <p>Subscribed on the Chunksmith instance, which is rebuilt on every server start, so these
     * die with it rather than accumulating across world loads.
     */
    private static void listenForProgress() {
        var api = ChunksmithProvider.get().getApi();
        api.onGenerationProgress(event -> {
            if (!ACTIVE.get() || !event.world().equals(worldKey)) {
                return;
            }
            chunksDone = event.chunks();
            percentComplete = event.progress();
            ETA.sample(System.currentTimeMillis(), event.chunks());
        });
        api.onGenerationComplete(event -> {
            if (ACTIVE.get() && event.world().equals(worldKey)) {
                recordCompletion();
                LOGGER.info("Chunksmith: world-enter pregen finished; releasing the world."
                        + " It will not run again on this world.");
                release();
            }
        });
    }

    /**
     * Marks this world as pre-generated so it is never pre-generated again.
     *
     * <p>A failure here is logged but NOT fatal: not recording a completion costs one redundant
     * pregen on the next load, which the player can skip with the button. Refusing to finish
     * because the bookkeeping failed would cost them the run they just waited for.
     */
    private static void recordCompletion() {
        MinecraftServer mcServer = server;
        if (mcServer == null) {
            return;
        }
        boolean recorded = new WorldEnterDone(worldKey, radiusBlocks, System.currentTimeMillis())
                .write(worldDir(mcServer));
        if (!recorded) {
            LOGGER.warn("Chunksmith: the world-enter pregen finished but its completion could not be"
                    + " recorded, so it may run again on the next load. Nothing is wrong with the"
                    + " generated chunks.");
        }
    }

    /**
     * Hands back anything a previous session borrowed and never returned.
     *
     * <p>A state file surviving startup means exactly one thing: we changed those
     * settings and the process did not live long enough to change them back.
     */
    private static void restoreAbandoned(MinecraftServer mcServer) {
        Path dir = worldDir(mcServer);
        Optional<WorldEnterState> abandoned = WorldEnterState.read(dir);
        if (abandoned.isEmpty()) {
            return;
        }
        LOGGER.info("Chunksmith: a previous session left this world mid-pregen. Restoring the"
                + " settings it had borrowed.");
        restoreFrom(mcServer, abandoned.get());
        WorldEnterState.clear(dir);
    }

    private static void restoreFrom(MinecraftServer mcServer, WorldEnterState state) {
        freeze(mcServer, false);
        if (state == null) {
            return;
        }
        ServerLevel level = mcServer.overworld();
        // The player's own value, never a vanilla default -- somebody may have turned time
        // advancement off on purpose, and silently switching it back on is not ours to do.
        //[[[cog
        // import cog, compat
        // cog.outl("        " + compat.gamerule_time_set(mcver, "level", "state.timeAdvanceWasOn()", "mcServer"))
        //]]]
        level.getGameRules().set(GameRules.ADVANCE_TIME, state.timeAdvanceWasOn(), mcServer);
        //[[[end]]]
        if (ChunksmithProvider.isLoaded()) {
            Config config = ChunksmithProvider.get().getConfig();
            config.setThrottleTickBudgetMillis(state.tickBudgetMillis());
            config.setThrottlePlayerReserveMillis(state.playerReserveMillis());
            config.setThrottleTargetMspt(state.targetMspt());
        }
    }

    // ---------------------------------------------------------------- the world

    private static void freeze(MinecraftServer mcServer, boolean frozen) {
        try {
            mcServer.tickRateManager().setFrozen(frozen);
            frozenByUs = frozen;
        } catch (RuntimeException e) {
            // Never let the freeze be the reason a world will not load.
            LOGGER.warn("Chunksmith: could not set the tick freeze ({}); carrying on unfrozen.",
                    e.toString());
            frozenByUs = false;
        }
    }

    /**
     * Stops time advancing, and puts the clock at dawn.
     *
     * <p>Set directly on the gamerule and the clock rather than through the
     * command interpreter, because that is permission-gated -- and this has to
     * work in a world created without cheats, which is most of them.
     */
    private static void stopTimeAdvancing(MinecraftServer mcServer, ServerLevel level) {
        //[[[cog
        // import cog, compat
        // cog.outl("        " + compat.gamerule_time_set(mcver, "level", "false", "mcServer"))
        //]]]
        level.getGameRules().set(GameRules.ADVANCE_TIME, false, mcServer);
        //[[[end]]]
        // 26 moved the day-time API onto a clock manager; before that ServerLevel owned it directly.
        // Present as setDayTime from 1.20.3 through 1.21.11, and gone at 26.
        //[[[cog
        // import cog, compat
        // if compat.has_world_clock(mcver):
        //     cog.outl("        Optional<Holder<WorldClock>> clock = level.dimensionType().defaultClock();")
        //     cog.outl("        clock.ifPresent(worldClock -> mcServer.clockManager().setTotalTicks(worldClock, 0L));")
        // else:
        //     cog.outl("        level.setDayTime(0L);")
        //]]]
        Optional<Holder<WorldClock>> clock = level.dimensionType().defaultClock();
        clock.ifPresent(worldClock -> mcServer.clockManager().setTotalTicks(worldClock, 0L));
        //[[[end]]]
    }

    /**
     * Gives the run the tick budget it can safely have while nobody is playing.
     *
     * <p><b>The write queue is deliberately left alone.</b> Raising
     * {@code throttleMaxQueuedWrites} was measured to make {@code save-all} block
     * past the 60-second watchdog and kill the server on shutdown. Write
     * backpressure is what protects the save path, and a save fires exactly when
     * the player presses the button.
     */
    private static void raiseThrottleForAnEmptyWorld(Config config) {
        config.setThrottleTickBudgetMillis(45L);
        config.setThrottlePlayerReserveMillis(0L);   // nobody is playing yet
        config.setThrottleTargetMspt(400.0);
    }

    // ---------------------------------------------------------------- the screen reads these

    public static boolean isActive() {
        return ACTIVE.get();
    }

    public static boolean isFrozen() {
        return frozenByUs;
    }

    public static long chunksTotal() {
        return chunksTotal;
    }

    /** Records progress and returns chunks done, for the bar and the estimate. */
    public static long chunksDone() {
        return chunksDone;
    }

    /**
     * The bar's fraction, 0..1.
     *
     * <p>Prefers the task's OWN percentage over our chunk estimate. estimateChunks() is a circle-area
     * approximation of a square-ish chunk iteration, so it is not exact, and a bar that fills past
     * the end -- or stops short of it -- is a bug the player can see. The estimate is only the
     * fallback for the window before the first progress event arrives.
     */
    public static double fraction() {
        float reported = percentComplete;
        if (reported > 0.0f) {
            return Math.max(0.0, Math.min(1.0, reported / 100.0));
        }
        return PregenEta.fraction(chunksDone, chunksTotal);
    }

    public static String eta() {
        return ETA.describe(chunksDone, chunksTotal);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Chunks inside a circle of this radius, which is what the bar is a fraction of.
     *
     * <p>An estimate on purpose: the real total depends on how much is already
     * generated, and the task only learns that as it scans. Close enough for a
     * progress bar, and the ETA is driven by observed rate rather than by this.
     */
    static long estimateChunks(long radiusBlocks) {
        double radiusChunks = radiusBlocks / 16.0;
        return (long) Math.ceil(Math.PI * radiusChunks * radiusChunks);
    }

    /**
     * The world's id as Chunksmith's task map keys it.
     *
     * <p>Cog-gated rather than hardcoded: {@code ResourceKey.location()} became
     * {@code identifier()} at the 1.21.11 rename. 26.x only needs the new name
     * today, but writing it this way is what makes backporting this feature to
     * the pre-26 cells a gate change rather than a code change.
     */
    private static String dimensionId(ServerLevel level) {
        //[[[cog
        // import cog, compat
        // cog.outl("return level.dimension().%s().toString();" % compat.dimension_identifier_call(mcver))
        //]]]
        //[[[end]]]
    }

    private static Path worldDir(MinecraftServer mcServer) {
        return mcServer.getWorldPath(LevelResource.ROOT);
    }
}
