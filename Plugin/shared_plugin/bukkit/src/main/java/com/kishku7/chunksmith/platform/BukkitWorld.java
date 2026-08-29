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

package com.kishku7.chunksmith.platform;

import org.bukkit.Chunk;
import org.bukkit.Effect;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.plugin.java.JavaPlugin;
import com.kishku7.chunksmith.ChunksmithBukkit;
import com.kishku7.chunksmith.Chunksmith;
import com.kishku7.chunksmith.lod.LodSupport;
import com.kishku7.chunksmith.platform.util.Location;
import com.kishku7.chunksmith.util.Input;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

public class BukkitWorld implements World {
    private static final boolean IS_GENERATED_SUPPORTED;
    private static final int TICKING_LOAD_DURATION = Input.tryInteger(System.getProperty("chunksmith.tickingLoadDuration")).orElse(0);
    private static final boolean AWAIT_TICKET_REMOVAL = Boolean.getBoolean("chunksmith.awaitTicketRemoval");
    private final JavaPlugin plugin = JavaPlugin.getPlugin(ChunksmithBukkit.class);
    private final org.bukkit.World world;
    private final Border worldBorder;

    static {
        boolean isGeneratedSupported;
        try {
            Chunk.class.getMethod("isGenerated");
            isGeneratedSupported = true;
        } catch (NoSuchMethodException e) {
            isGeneratedSupported = false;
        }
        IS_GENERATED_SUPPORTED = isGeneratedSupported;
    }

    public BukkitWorld(org.bukkit.World world) {
        this.world = world;
        this.worldBorder = new BukkitBorder(world.getWorldBorder());
    }

    @Override
    public String getName() {
        return world.getName();
    }

    @Override
    public String getKey() {
        return world.getKey().toString();
    }

    @Override
    public CompletableFuture<Boolean> isChunkGenerated(int x, int z) {
        if (Paper.isPaper()) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return world.isChunkGenerated(x, z);
                } catch (CompletionException e) {
                    return false;
                }
            });
        } else {
            if (IS_GENERATED_SUPPORTED) {
                return CompletableFuture.completedFuture(world.getChunkAt(x, z, false).isGenerated());
            } else {
                return CompletableFuture.completedFuture(false);
            }
        }
    }

    private CompletableFuture<Void> getChunkFuture(int x, int z) {
        CompletableFuture<Chunk> rawFuture;
        if (Paper.isPaper()) {
            rawFuture = Paper.getChunkAtAsync(world, x, z);
        } else {
            rawFuture = CompletableFuture.completedFuture(world.getChunkAt(x, z));
        }

        // Offer every chunk this method resolves to the CSLOD extractor (mod_support #9 follow-up;
        // server-side generation only; see com.kishku7.chunksmith.lod.LodSupport / CsLodExtractor).
        // This IS the pregen chunk-dispatch path: GenerationTask calls getChunkAtAsync to fetch or
        // generate each chunk, the same place FabricWorld/NeoForgeWorld/ForgeWorld hook LodSupport.offer.
        // thenAccept both supplies the CompletableFuture<Void> this method must return and runs the offer
        // as its side effect.
        return rawFuture.thenAccept(chunk -> {
            Chunksmith chunky = ((ChunksmithBukkit) plugin).getChunky();
            if (chunky != null) {
                // GenerationTask does not always .join() or .exceptionally() this future per chunk, so an
                // uncaught throwable out of offer() would complete it exceptionally with no log line
                // anywhere. Caught, logged with the chunk coords, swallowed: LOD extraction must not break
                // pregen, and it must not fail silently either.
                try {
                    LodSupport.offer(chunky.getConfig(), world, chunk);
                } catch (Throwable t) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING,
                            "Chunksmith: LOD extraction failed for chunk " + chunk.getX() + "," + chunk.getZ()
                                    + " in " + world.getName() + "; LOD skipped for this chunk, generation unaffected", t);
                }
            }
        });
    }

    @Override
    public CompletableFuture<Void> getChunkAtAsync(int x, int z) {
        CompletableFuture<Void> chunkFuture = this.getChunkFuture(x, z);
        if (TICKING_LOAD_DURATION > 0) {
            CompletableFuture<Void> removeTicketFuture = new CompletableFuture<>();
            chunkFuture.thenAccept(ignored -> {
                Runnable addTicketTask = () -> world.addPluginChunkTicket(x, z, plugin);
                Runnable removeTicketTask = () -> {
                    world.removePluginChunkTicket(x, z, plugin);
                    removeTicketFuture.complete(null);
                };
                if (Folia.isFolia()) {
                    org.bukkit.Location location = new org.bukkit.Location(world, x << 4, 0, z << 4);
                    Folia.schedule(plugin, location, addTicketTask);
                    CompletableFuture.runAsync(() -> Folia.schedule(plugin, location, removeTicketTask), CompletableFuture.delayedExecutor(TICKING_LOAD_DURATION, TimeUnit.SECONDS));
                } else {
                    plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, addTicketTask);
                    plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, removeTicketTask, TICKING_LOAD_DURATION * 20L);
                }
            });
            if (AWAIT_TICKET_REMOVAL) {
                return removeTicketFuture;
            }
        }
        return chunkFuture;
    }

    @Override
    public UUID getUUID() {
        return world.getUID();
    }

    @Override
    public int getSeaLevel() {
        return world.getSeaLevel();
    }

    @Override
    public Location getSpawn() {
        org.bukkit.Location spawnLocation = world.getSpawnLocation();
        return new Location(this, spawnLocation.getX(), spawnLocation.getY(), spawnLocation.getZ(), spawnLocation.getYaw(), spawnLocation.getPitch());
    }

    @Override
    public Border getWorldBorder() {
        return worldBorder;
    }

    @Override
    public int getElevation(int x, int z) {
        if (Folia.isFolia() && !Folia.isTickThread(this.world, x >> 4, z >> 4)) {
            throw new IllegalStateException("Async getElevation call");
        } else {
            return getElevationForLocation(x, z);
        }
    }

    @Override
    public CompletableFuture<Integer> getElevationAtAsync(int x, int z) {
        int chunkX = x >> 4;
        int chunkZ = z >> 4;

        return this.getChunkFuture(chunkX, chunkZ).thenApplyAsync(ignored -> this.getElevationForLocation(x, z), getMainThreadExecutor(chunkX, chunkZ));
    }

    private int getElevationForLocation(int x, int z) {
        int height = world.getHighestBlockYAt(x, z) + 1;
        int logicalHeight = world.getLogicalHeight();
        if (height >= logicalHeight) {
            Block block = world.getBlockAt(x, logicalHeight, z);
            int air = 0;
            while (block.getY() > world.getMinHeight()) {
                block = block.getRelative(BlockFace.DOWN);
                Material type = block.getType();
                if (type.isSolid() && air > 1) {
                    return block.getY() + 1;
                }
                air = type.isAir() ? air + 1 : 0;
            }
        }
        return height;
    }

    /**
     * {@return a new executor that executes tasks on the main thread}
     * <p>
     * Must not be passed around different threads, since it shortcuts to immediately running tasks if the calling thread is already a main thread.
     *
     * @param chunkX The chunk x coord (for Folia)
     * @param chunkZ The chunk z coord (for Folia)
     */
    private Executor getMainThreadExecutor(int chunkX, int chunkZ) {
        if (!Folia.isFolia()) {
            if (plugin.getServer().isPrimaryThread()) {
                return Runnable::run;
            } else {
                return runnable -> plugin.getServer().getScheduler().runTask(plugin, runnable);
            }
        }

        if (Folia.isTickThread(world, chunkX, chunkZ)) {
            return Runnable::run;
        } else {
            return runnable -> Folia.schedule(plugin, world, chunkX, chunkZ, runnable);
        }
    }

    @Override
    public int getMaxElevation() {
        return world.getLogicalHeight();
    }

    @Override
    public void playEffect(Player player, String effect) {
        Effect effectType;
        try {
            effectType = Effect.valueOf(effect.toUpperCase());
        } catch (IllegalArgumentException e) {
            return;
        }
        Location location = player.getLocation();
        org.bukkit.Location bukkitLocation = new org.bukkit.Location(world, location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
        world.playEffect(bukkitLocation, effectType, 0);
    }

    @Override
    // Sound.valueOf is deprecated-for-removal (Sound became a registry interface) but retained for cross-version
    // string->Sound lookup; the Registry-based replacement is absent on older targeted servers.
    @SuppressWarnings({"deprecation", "removal"})
    public void playSound(Player player, String sound) {
        Sound soundType;
        try {
            soundType = Sound.valueOf(sound.toUpperCase());
        } catch (IllegalArgumentException e) {
            return;
        }
        Location location = player.getLocation();
        org.bukkit.Location bukkitLocation = new org.bukkit.Location(world, location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
        world.playSound(bukkitLocation, soundType, 2f, 1f);
    }

    // --- write-queue backpressure (reflective; reaches the vanilla IOWorker pending-write
    // map via CraftWorld#getHandle -> getChunkSource -> chunkMap -> worker -> pendingWrites).
    // Fail-safe: any failure (Moonrise/Folia/remapped internals) disables the probe and
    // returns -1, so the throttle falls back to its tick-health backstop. Never throws. ---
    private static volatile boolean writeQueueProbeDisabled = false;
    private static volatile Method getHandleMethod;
    private static volatile Method getChunkSourceMethod;
    private static volatile Field chunkMapField;
    private static volatile Field workerField;
    private static volatile Field pendingWritesField;

    @Override
    public long getQueuedChunkWrites() {
        if (writeQueueProbeDisabled) {
            return -1;
        }
        try {
            Method gh = getHandleMethod;
            if (gh == null) {
                gh = world.getClass().getMethod("getHandle");
                getHandleMethod = gh;
            }
            Object serverLevel = gh.invoke(world);
            Method gcs = getChunkSourceMethod;
            if (gcs == null) {
                gcs = findMethod(serverLevel.getClass(), "getChunkSource");
                getChunkSourceMethod = gcs;
            }
            Object chunkSource = gcs.invoke(serverLevel);
            Field cmf = chunkMapField;
            if (cmf == null) {
                cmf = findField(chunkSource.getClass(), "chunkMap");
                chunkMapField = cmf;
            }
            Object chunkMap = cmf.get(chunkSource);
            Field wf = workerField;
            if (wf == null) {
                wf = findField(chunkMap.getClass(), "worker");
                workerField = wf;
            }
            Object worker = wf.get(chunkMap);
            if (worker == null) {
                return -1;
            }
            Field pwf = pendingWritesField;
            if (pwf == null) {
                pwf = findField(worker.getClass(), "pendingWrites");
                pendingWritesField = pwf;
            }
            Object pending = pwf.get(worker);
            if (pending instanceof Map<?, ?> map) {
                return map.size();
            }
            return -1;
        } catch (Throwable t) {
            writeQueueProbeDisabled = true;
            return -1;
        }
    }

    private static Method findMethod(Class<?> from, String name) throws NoSuchMethodException {
        for (Class<?> k = from; k != null; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static Field findField(Class<?> from, String name) throws NoSuchFieldException {
        for (Class<?> k = from; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    @Override
    public Optional<Path> getDirectory(String name) {
        if (name == null) {
            return Optional.empty();
        }
        org.bukkit.World.Environment environment = world.getEnvironment();
        String parent;
        if (org.bukkit.World.Environment.NETHER.equals(environment)) {
            parent = "DIM-1";
        } else if (org.bukkit.World.Environment.THE_END.equals(environment)) {
            parent = "DIM1";
        } else {
            parent = "";
        }
        Path bukkitDir = world.getWorldFolder().toPath().resolve(parent).normalize().resolve(name);
        Path vanillaDir = world.getWorldFolder().toPath().normalize().resolve(name);
        Path directory = Files.isDirectory(bukkitDir) ? bukkitDir : vanillaDir;
        return Files.isDirectory(directory) ? Optional.of(directory) : Optional.empty();
    }
}
