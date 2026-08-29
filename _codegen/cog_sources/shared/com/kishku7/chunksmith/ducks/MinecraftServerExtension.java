/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) 2025-2026 Kishku7
 * Copyright (C) pop4959 and contributors.
 *
 * This file is derived from Chunky (https://github.com/pop4959/Chunky).
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

package com.kishku7.chunksmith.ducks;

import java.util.function.BooleanSupplier;

public interface MinecraftServerExtension {
    void chunksmith$runChunkSystemHousekeeping(BooleanSupplier haveTime);

    void chunksmith$markChunkSystemHousekeeping();

    /**
     * Being on the server thread is not enough (mod_support #16). For the whole of {@code
     * ServerChunkCache.tickChunks} the server thread is inside one fastutil walk of {@code
     * simulationChunkTracker.chunks}. A ticket add or remove queues a level update, and the
     * next pump of the chunk-source executor applies it ({@code MainThreadExecutor.pollTask()}
     * runs {@code runDistanceManagerUpdates()} first), both inside the walk, so the map is
     * structurally modified mid-iteration: "Cannot invoke LongArrayList.getLong(int) because
     * this.wrapped is null". A pre-gen mutates tickets every tick; C2ME pumps often enough to
     * collect on it. So ticket work runs here instead, once per tick from the {@code
     * tickServer} housekeeping hook, outside every chunk-system iteration.
     *
     * <p>Safe to call from any thread.
     */
    void chunksmith$atTicketSafePoint(Runnable task);

    /**
     * For ONE caller: the paused integrated server. The ordinary drain rides {@code
     * MinecraftServer.tickServer} HEAD, which {@code IntegratedServer.tickServer} never reaches
     * while paused, so the queue filled, nothing emptied it, and nothing threw (mod_support
     * #17, regression from 3.3.0's safe point). Safe precisely because the server is paused:
     * {@code tickChunks}, whose walk the safe point protects, does not run. Propagation is
     * unchanged -- the outer {@code runServer} loop keeps pumping {@code pollTask()}.
     *
     * <p>Server thread only.
     */
    void chunksmith$drainTicketSafePointNow();

    boolean chunksmith$onTicketSafePoint();

    /**
     * Returns the smoothed mean ms-per-tick of the main thread, sampled only while a generation
     * task is active. Primary I/O-throttle signal. At or near 50 ms is a full 20 TPS, higher is
     * falling behind.
     */
    double chunksmith$getMillisPerTick();
}
