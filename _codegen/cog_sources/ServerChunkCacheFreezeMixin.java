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

package com.kishku7.chunksmith.mixin;

import com.kishku7.chunksmith.util.FrozenTicketPurge;
import com.kishku7.chunksmith.worldenter.WorldEnterPregen;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
//[[[cog
// import cog, compat
// shape = compat.ticket_purge_shape(mcver)
// if shape == "dm":
//     cog.outl("import net.minecraft.server.level.DistanceManager;")
// else:
//     cog.outl("import net.minecraft.server.level.ChunkMap;")
//     cog.outl("import net.minecraft.world.level.TicketStorage;")
//]]]
//[[[end]]]
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Keeps chunk tickets EXPIRING while the world-enter pregen holds the tick freeze (issue #37).
 *
 * <p>{@code ServerChunkCache.tick} purges stale tickets only when
 * {@code tickRateManager().runsNormally() || !tickChunks}. That is right for {@code /tick freeze},
 * where nothing is loading. It is wrong for the world-enter run, which borrows the same freeze
 * while loading tens of thousands of chunks: every timed ticket the task placed stayed alive, so no
 * chunk ever unloaded. Measured on NeoForge 1.21.1 -- resident chunks 14,669 -> 27,418 and live heap
 * 1,584 -> 3,053 MB between minute 3 and minute 15, unload queue empty, our own ticket ledger at
 * zero. The heap guard then throttled the run to a crawl, which is the "slows down after an hour"
 * the reporter saw.
 *
 * <p>This does the one thing vanilla skipped, at the head of the same method, under the exact
 * complement of vanilla's guard, and only while {@link WorldEnterPregen#isFrozen()} is set --
 * {@code /tick freeze} is untouched. It does not purge unconditionally: the resident chunks are the
 * neighbours the next ring needs, so {@link FrozenTicketPurge} keeps them up to a heap-sized cap and
 * purges every tick (vanilla's unfrozen behaviour) above it.
 *
 * <p>{@code @Inject}, not {@code @Redirect}: large packs carry other mods that touch this method,
 * and an inject coexists with them where a redirect would conflict.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheFreezeMixin {

    @Unique
    private static final Logger chunksmith$LOGGER = LoggerFactory.getLogger("Chunksmith");

    @Unique
    private static volatile boolean chunksmith$announced;

    @Unique
    private static volatile boolean chunksmith$disabled;

    /** Per dimension (each level has its own cache), server thread only. See FrozenTicketPurge. */
    @Unique
    private final FrozenTicketPurge chunksmith$policy = new FrozenTicketPurge();

    @Shadow
    @Final
    ServerLevel level;

    //[[[cog
    // import cog, compat
    // shape = compat.ticket_purge_shape(mcver)
    // if shape == "dm":
    //     cog.outl("    @Shadow")
    //     cog.outl("    @Final")
    //     cog.outl("    private DistanceManager distanceManager;")
    // else:
    //     cog.outl("    @Shadow")
    //     cog.outl("    @Final")
    //     cog.outl("    private TicketStorage ticketStorage;")
    //     cog.outl("")
    //     cog.outl("    @Shadow")
    //     cog.outl("    @Final")
    //     cog.outl("    public ChunkMap chunkMap;")
    //]]]
    //[[[end]]]

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;Z)V", at = @At("HEAD"))
    private void chunksmith$purgeWhileFrozen(BooleanSupplier hasTimeLeft, boolean tickChunks, CallbackInfo ci) {
        if (chunksmith$disabled || !tickChunks) {
            return;
        }
        if (!WorldEnterPregen.isFrozen() || this.level.tickRateManager().runsNormally()) {
            chunksmith$policy.reset();
            return;
        }
        int resident = ((ServerChunkCache) (Object) this).getLoadedChunksCount();
        if (!chunksmith$policy.tick(resident, Runtime.getRuntime().maxMemory())) {
            return;
        }
        try {
            this.chunksmith$purge();
        } catch (Throwable t) {
            chunksmith$disabled = true;
            chunksmith$LOGGER.warn("Chunksmith: could not expire chunk tickets during the world-enter"
                    + " freeze ({}); chunks will stay loaded until the world is released.", t.toString());
            return;
        }
        if (!chunksmith$announced) {
            chunksmith$announced = true;
            chunksmith$LOGGER.info("Chunksmith: expiring chunk tickets during the world-enter freeze"
                    + " so finished chunks can unload ({} resident, cap {}).", resident,
                    FrozenTicketPurge.effectiveCap(Runtime.getRuntime().maxMemory()));
        }
    }
    //[[[cog
    // import cog, compat
    // shape = compat.ticket_purge_shape(mcver)
    // if shape == "dm":
    //     cog.outl("    @Unique")
    //     cog.outl("    private void chunksmith$purge() {")
    //     cog.outl("        ((DistanceManagerPurgeInvoker) (Object) this.distanceManager).chunksmith$invokePurgeStaleTickets();")
    //     cog.outl("    }")
    // elif shape == "ts0":
    //     cog.outl("    @Unique")
    //     cog.outl("    private void chunksmith$purge() {")
    //     cog.outl("        this.ticketStorage.purgeStaleTickets();")
    //     cog.outl("    }")
    // else:
    //     cog.outl("    @Unique")
    //     cog.outl("    private static volatile java.lang.reflect.Method chunksmith$noArgPurge;")
    //     cog.outl("")
    //     cog.outl("    @Unique")
    //     cog.outl("    private void chunksmith$purge() throws ReflectiveOperationException {")
    //     cog.outl("        java.lang.reflect.Method noArg = chunksmith$noArgPurge;")
    //     cog.outl("        if (noArg != null) {")
    //     cog.outl("            noArg.invoke(this.ticketStorage);")
    //     cog.outl("            return;")
    //     cog.outl("        }")
    //     cog.outl("        try {")
    //     cog.outl("            this.ticketStorage.purgeStaleTickets(this.chunkMap);")
    //     cog.outl("        } catch (NoSuchMethodError e) {")
    //     cog.outl("            // A cell spanning 1.21.5 (NeoForge/1.21.8) running on 1.21.5, where the purge takes no")
    //     cog.outl("            // argument. NeoForge runs mojmap names, so the name resolves at runtime.")
    //     cog.outl("            noArg = TicketStorage.class.getMethod(\"purgeStaleTickets\");")
    //     cog.outl("            chunksmith$noArgPurge = noArg;")
    //     cog.outl("            noArg.invoke(this.ticketStorage);")
    //     cog.outl("        }")
    //     cog.outl("    }")
    //]]]
    //[[[end]]]
}
