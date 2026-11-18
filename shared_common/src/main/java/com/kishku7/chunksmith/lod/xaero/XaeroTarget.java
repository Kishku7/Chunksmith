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

package com.kishku7.chunksmith.lod.xaero;

import com.kishku7.chunksmith.lod.CsLodChunk;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Puts CSLOD terrain onto Xaero's World Map by writing its region files and telling it they are there.
 *
 * <p>World Map has no API for third-party map data, so this works the way World Map itself does: a region is a
 * {@code <rx>_<rz>.zip} in the folder for the current world, dimension and multiworld, and the map loads whatever
 * it has registered. Every call below is to a public method of World Map's own classes, found by name, so
 * Chunksmith neither compiles against nor ships any of it, and a World Map that changes a name costs us the
 * feature, never the game ({@link #available()} turns false and the cause is logged once).
 *
 * <p>Tiles World Map already holds, drawn from chunks the client really loaded, are never replaced; ours fill the
 * tiles it lacks. The file format and the hooks used are described in the Xaero region codec and tile builder classes next to this one.
 */
public final class XaeroTarget {

    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");

    /** World Map's key for the normal (non-cave) layer. */
    private static final int SURFACE_LAYER = Integer.MAX_VALUE;

    /** What happened to one region. */
    public enum Outcome {
        /** The region file was written and World Map told about it. */
        WROTE,
        /** Every tile of ours was already on the map; nothing to do. */
        UNCHANGED,
        /** World Map is not in a state to take it (no world yet, another dimension): try again later. */
        NOT_READY,
        /** Something went wrong; see the log. */
        FAILED
    }

    private static final AtomicLong regionsWritten = new AtomicLong();
    private static final AtomicLong tilesAdded = new AtomicLong();
    private static final AtomicLong tilesKept = new AtomicLong();
    private static final AtomicInteger failures = new AtomicInteger();
    private static volatile String lastFailure = "";
    private static volatile boolean brokenLogged;

    private XaeroTarget() {
    }

    // ---- availability -----------------------------------------------------------------------------------

    /** True when World Map is loaded, has a world open, and has finished looking for existing region files. */
    public static boolean available() {
        try {
            return session() != null && ready(processor());
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            noteBroken(e);
            return false;
        }
    }

    private static Object session() throws ReflectiveOperationException {
        Class<?> type = Class.forName("xaero.map.WorldMapSession");
        return type.getMethod("getCurrentSession").invoke(null);
    }

    private static Object processor() throws ReflectiveOperationException {
        Object session = session();
        return session == null ? null : call(session, "getMapProcessor");
    }

    private static boolean ready(final Object processor) throws ReflectiveOperationException {
        if (processor == null) {
            return false;
        }
        Object saveLoad = call(processor, "getMapSaveLoad");
        return saveLoad != null && Boolean.TRUE.equals(call(saveLoad, "isRegionDetectionComplete"))
                && call(processor, "getCurrentWorldId") != null;
    }

    // ---- injection --------------------------------------------------------------------------------------

    /**
     * Adds a CSLOD region's chunks to the map. Safe off the game thread: the file work runs on the caller, and
     * the calls into World Map that need its main thread are queued to it and awaited.
     *
     * @param dimension the CSLOD dimension directory name, e.g. {@code minecraft_overworld}; must be the
     *                  dimension the player is in
     */
    public static Outcome inject(final String dimension, final int regionX, final int regionZ,
                                 final List<CsLodChunk> chunks) {
        if (!supportsDimension(dimension)) {
            return Outcome.UNCHANGED;
        }
        try {
            Object processor = processor();
            if (!ready(processor)) {
                return Outcome.NOT_READY;
            }
            String xaeroDim = (String) call(processor, "getCurrentDimId");
            if (xaeroDim == null || !xaeroDim.equals(folderName(dimension))) {
                return Outcome.NOT_READY;
            }
            Object saveLoad = call(processor, "getMapSaveLoad");
            String worldId = (String) call(processor, "getCurrentWorldId");
            String mwId = (String) call(processor, "getCurrentMWId");
            Path folder = (Path) saveLoad.getClass().getMethod("getMWSubFolder", String.class, String.class, String.class)
                    .invoke(saveLoad, worldId, xaeroDim, mwId);
            if (folder == null) {
                return Outcome.NOT_READY;
            }
            Files.createDirectories(folder);
            Path file = folder.resolve(regionX + "_" + regionZ + ".zip");

            byte[] existing = null;
            if (Files.isRegularFile(file)) {
                try {
                    existing = XaeroRegionCodec.unzip(Files.readAllBytes(file));
                } catch (IOException e) {
                    // A file we cannot read back exactly is World Map's own or a newer format. Leave it alone.
                    LOGGER.debug("Chunksmith: not touching Xaero region {} {}: {}", regionX, regionZ, e.toString());
                    return Outcome.UNCHANGED;
                }
            }
            Path ownedFile = folder.resolve(regionX + "_" + regionZ + ".csown");
            int[] ownedBefore = existing == null ? null : readOwned(ownedFile);
            XaeroRegionWriter.Region region;
            try {
                region = XaeroRegionWriter.build(regionX, regionZ, chunks, existing, ownedBefore);
            } catch (IOException e) {
                LOGGER.debug("Chunksmith: Xaero region {} {} could not be merged: {}", regionX, regionZ, e.toString());
                return Outcome.UNCHANGED;
            }
            if (region.unchanged()) {
                return Outcome.UNCHANGED;
            }
            Path temp = folder.resolve(regionX + "_" + regionZ + ".zip.chunksmith");
            Files.write(temp, XaeroRegionCodec.zip(region.xaeroBytes));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            writeOwned(ownedFile, region.owned);
            regionsWritten.incrementAndGet();
            tilesAdded.addAndGet(region.tilesAdded);
            tilesKept.addAndGet(region.tilesKept);

            File fileHandle = file.toFile();
            onMainThread(() -> register(regionX, regionZ, fileHandle));
            return Outcome.WROTE;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException | IOException e) {
            failures.incrementAndGet();
            lastFailure = e.toString();
            noteBroken(e);
            return Outcome.FAILED;
        }
    }

    /** The tile hashes we recorded beside a region file, or null when there is no readable record. */
    private static int[] readOwned(final Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            byte[] raw = Files.readAllBytes(file);
            if (raw.length != 4 * XaeroRegionCodec.TILES * XaeroRegionCodec.TILES) {
                return null;
            }
            int[] hashes = new int[XaeroRegionCodec.TILES * XaeroRegionCodec.TILES];
            java.nio.ByteBuffer.wrap(raw).asIntBuffer().get(hashes);
            return hashes;
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeOwned(final Path file, final int[] hashes) {
        try {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(hashes.length * 4);
            buffer.asIntBuffer().put(hashes);
            Files.write(file, buffer.array());
        } catch (IOException e) {
            LOGGER.debug("Chunksmith: could not record the tiles written for Xaero region {}: {}", file.getFileName(), e.toString());
        }
    }

    /**
     * Whether the surface layer is the right thing to feed in this dimension. The Nether has a bedrock roof, so a
     * top-down scan would map the ceiling, and World Map shows the Nether through its cave layers (chosen by the
     * player's height), which this does not write yet. Feeding the wrong picture is worse than none.
     */
    static boolean supportsDimension(final String csDimension) {
        return !"minecraft_the_nether".equals(csDimension);
    }

    /**
     * Tells World Map the region file exists. A region it has not met is registered as a detection, which is
     * exactly what its own world-open scan does per file; one it already holds is marked outdated and queued for
     * a reload so it reads our file instead of what it has in memory.
     */
    private static void register(final int regionX, final int regionZ, final File file) {
        try {
            Object processor = processor();
            if (!ready(processor)) {
                return;
            }
            Object region = call(processor, "getLeafMapRegion",
                    new Class<?>[] {int.class, int.class, int.class, boolean.class},
                    SURFACE_LAYER, regionX, regionZ, false);
            Object dimension = call(call(processor, "getMapWorld"), "getCurrentDimension");
            Object layer = call(call(dimension, "getLayeredMapRegions"), "getLayer",
                    new Class<?>[] {int.class}, SURFACE_LAYER);
            if (region == null) {
                Object known = call(layer, "getRegionDetection", new Class<?>[] {int.class, int.class}, regionX, regionZ);
                Object complete = call(layer, "getCompleteRegionDetection", new Class<?>[] {int.class, int.class}, regionX, regionZ);
                if (known == null && complete == null) {
                    Class<?> detectionType = Class.forName("xaero.map.file.RegionDetection");
                    Constructor<?> ctor = detectionType.getConstructor(String.class, String.class, String.class,
                            int.class, int.class, File.class, int.class, boolean.class);
                    Object detection = ctor.newInstance(call(processor, "getCurrentWorldId"),
                            call(processor, "getCurrentDimId"), call(processor, "getCurrentMWId"),
                            regionX, regionZ, file, call(processor, "getGlobalVersion"), true);
                    call(layer, "addRegionDetection", new Class<?>[] {detectionType}, detection);
                }
                return;
            }
            Object saveLoad = call(processor, "getMapSaveLoad");
            synchronized (region) {
                byte state = (Byte) call(region, "getLoadState");
                if (Boolean.TRUE.equals(call(region, "isBeingWritten")) && state == 1) {
                    return;
                }
                if (call(region, "getCacheFile") != null) {
                    call(region, "convertCacheToOutdated", new Class<?>[] {saveLoad.getClass(), String.class},
                            saveLoad, "chunksmith");
                }
                call(region, "setLoadState", new Class<?>[] {byte.class}, (byte) 4);
            }
            call(saveLoad, "requestLoad", new Class<?>[] {region.getClass(), String.class, boolean.class},
                    region, "chunksmith", false);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            failures.incrementAndGet();
            lastFailure = e.toString();
            noteBroken(e);
        }
    }

    // ---- debug helpers (the /csclient xaero command) ------------------------------------------------------

    /**
     * Opens World Map's full-screen map. Returns a short message either way.
     *
     * @param zoom World Map's zoom (pixels per block; its default is 3, 0.1 shows several thousand blocks), or
     *             a value of zero or less to leave it as the player last set it
     */
    public static String openMap(final double zoom) {
        try {
            Object processor = processor();
            if (processor == null) {
                return "World Map has no world open";
            }
            Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft");
            Object mc = minecraft.getMethod("getInstance").invoke(null);
            Class<?> screenType = Class.forName("net.minecraft.client.gui.screens.Screen");
            Class<?> entityType = Class.forName("net.minecraft.world.entity.Entity");
            Class<?> guiType = Class.forName("xaero.map.gui.GuiMap");
            if (zoom > 0.0) {
                java.lang.reflect.Field scale = guiType.getDeclaredField("destScale");
                scale.setAccessible(true);
                scale.setDouble(null, zoom);
            }
            Constructor<?> ctor = guiType.getConstructor(screenType, screenType, processor.getClass(), entityType);
            Object player = minecraft.getField("player").get(mc);
            Object screen = ctor.newInstance(null, null, processor, player);
            onMainThread(() -> {
                try {
                    // 26.x calls it setScreenAndShow; earlier versions setScreen. Take whichever takes a Screen.
                    Method show = null;
                    for (String name : new String[] {"setScreenAndShow", "setScreen"}) {
                        for (Method m : minecraft.getMethods()) {
                            if (m.getName().equals(name) && m.getParameterCount() == 1
                                    && m.getParameterTypes()[0].isAssignableFrom(screenType)) {
                                show = m;
                            }
                        }
                        if (show != null) {
                            break;
                        }
                    }
                    if (show == null) {
                        throw new NoSuchMethodException("Minecraft#setScreenAndShow/setScreen");
                    }
                    show.invoke(mc, screen);
                } catch (ReflectiveOperationException e) {
                    LOGGER.warn("Chunksmith: could not open the World Map screen: {}", e.toString());
                }
            });
            return "opening the World Map";
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return "could not open the World Map: " + e;
        }
    }

    /** One line for logs and {@code /csclient status}. */
    public static String describe() {
        return "xaero: " + (available() ? "ready" : "not ready") + ", " + regionsWritten.get() + " region file(s) written, "
                + tilesAdded.get() + " tile(s) added, " + tilesKept.get() + " kept from World Map"
                + (failures.get() > 0 ? ", " + failures.get() + " failure(s), last: " + lastFailure : "");
    }

    // ---- reflection plumbing ----------------------------------------------------------------------------

    /** The folder name World Map gives a dimension: {@code null} for the overworld, {@code DIM-1}, {@code DIM1}, else {@code ns$path}. */
    static String folderName(final String csDimension) {
        switch (csDimension) {
            case "minecraft_overworld":
                return "null";
            case "minecraft_the_nether":
                return "DIM-1";
            case "minecraft_the_end":
                return "DIM1";
            default:
                int sep = csDimension.indexOf('_');
                return sep < 0 ? csDimension : csDimension.substring(0, sep) + "$" + csDimension.substring(sep + 1);
        }
    }

    private static Object call(final Object target, final String name) throws ReflectiveOperationException {
        return call(target, name, new Class<?>[0]);
    }

    private static Object call(final Object target, final String name, final Class<?>[] types, final Object... args)
            throws ReflectiveOperationException {
        try {
            Method method = findMethod(target.getClass(), name, types);
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }

    private static Method findMethod(final Class<?> type, final String name, final Class<?>[] types) throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method m : c.getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == types.length && assignable(m.getParameterTypes(), types)) {
                    return m;
                }
            }
        }
        throw new NoSuchMethodException(type.getName() + "#" + name);
    }

    private static boolean assignable(final Class<?>[] declared, final Class<?>[] wanted) {
        for (int i = 0; i < declared.length; i++) {
            if (!declared[i].isAssignableFrom(wanted[i]) && !wanted[i].isAssignableFrom(declared[i])) {
                return false;
            }
        }
        return true;
    }

    private static void onMainThread(final Runnable task) throws ReflectiveOperationException {
        Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft");
        Object mc = minecraft.getMethod("getInstance").invoke(null);
        minecraft.getMethod("execute", Runnable.class).invoke(mc, task);
    }

    private static void noteBroken(final Throwable cause) {
        if (!brokenLogged) {
            brokenLogged = true;
            LOGGER.warn("Chunksmith: the Xaero's World Map hooks failed ({}). The far map will not be filled this session;"
                    + " everything else works. Please report this with your World Map version.", cause.toString());
        }
    }
}
