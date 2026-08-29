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

package com.kishku7.chunksmith.command;

import com.kishku7.chunksmith.Chunksmith;
import com.kishku7.chunksmith.Selection;
import com.kishku7.chunksmith.nbt.LongTag;
import com.kishku7.chunksmith.nbt.Tag;
import com.kishku7.chunksmith.nbt.TagType;
import com.kishku7.chunksmith.nbt.util.ChunkFilter;
import com.kishku7.chunksmith.nbt.util.RegionFile;
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.platform.World;
import com.kishku7.chunksmith.shape.Shape;
import com.kishku7.chunksmith.shape.ShapeFactory;
import com.kishku7.chunksmith.shape.ShapeType;
import com.kishku7.chunksmith.util.ChunkCoordinate;
import com.kishku7.chunksmith.util.Formatting;
import com.kishku7.chunksmith.util.Input;
import com.kishku7.chunksmith.util.TranslationKey;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static com.kishku7.chunksmith.util.Translator.translate;

public class TrimCommand implements ChunksmithCommand {
    private final Chunksmith chunky;

    public TrimCommand(Chunksmith chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(Sender sender, CommandArguments arguments) {
        if (arguments.size() > 0) {
            Optional<World> world = arguments.next().flatMap(arg -> Input.tryWorld(chunky, arg));
            if (world.isPresent()) {
                chunky.getSelection().world(world.get());
            } else {
                sender.sendMessage(TranslationKey.HELP_TRIM);
                return;
            }
        }
        if (arguments.size() > 1) {
            Optional<String> shape = arguments.next().flatMap(Input::tryShape);
            if (shape.isPresent()) {
                chunky.getSelection().shape(shape.get());
            } else {
                sender.sendMessage(TranslationKey.HELP_TRIM);
                return;
            }
        }
        if (arguments.size() > 2) {
            Optional<Double> centerX = arguments.next().flatMap(Input::tryDoubleSuffixed).filter(c -> !Input.isPastWorldLimit(c));
            Optional<Double> centerZ = arguments.next().flatMap(Input::tryDoubleSuffixed).filter(c -> !Input.isPastWorldLimit(c));
            if (centerX.isPresent() && centerZ.isPresent()) {
                chunky.getSelection().center(centerX.get(), centerZ.get());
            } else {
                sender.sendMessage(TranslationKey.HELP_TRIM);
                return;
            }
        }
        if (arguments.size() > 4) {
            Optional<Double> radiusX = arguments.next().flatMap(Input::tryDoubleSuffixed).filter(r -> r >= 0 && !Input.isPastWorldLimit(r));
            if (radiusX.isPresent()) {
                chunky.getSelection().radius(radiusX.get());
            } else {
                sender.sendMessage(TranslationKey.HELP_TRIM);
                return;
            }
        }
        if (arguments.size() > 5) {
            Optional<Double> radiusZ = arguments.next().flatMap(Input::tryDoubleSuffixed).filter(r -> r >= 0 && !Input.isPastWorldLimit(r));
            if (radiusZ.isPresent()) {
                chunky.getSelection().radiusZ(radiusZ.get());
            } else {
                sender.sendMessage(TranslationKey.HELP_TRIM);
                return;
            }
        }
        boolean inside;
        if (arguments.size() > 6) {
            Optional<String> side = arguments.next().map(String::toLowerCase).filter(s -> "outside".equals(s) || "inside".equals(s));
            if (side.isPresent()) {
                inside = side.map("inside"::equals).orElse(false);
            } else {
                sender.sendMessage(TranslationKey.HELP_TRIM);
                return;
            }
        } else {
            inside = false;
        }
        int inhabitedTime = arguments.next().flatMap(Input::tryIntegerSuffixed).orElse(Integer.MAX_VALUE);
        boolean inhabitedTimeCheck = inhabitedTime < Integer.MAX_VALUE;
        Selection selection = chunky.getSelection().build();
        Shape shape = ShapeFactory.getShape(selection);
        Runnable deletionAction = () -> chunky.getScheduler().runTask(() -> {
            sender.sendMessagePrefixed(TranslationKey.FORMAT_START, selection.world().getName(), translate("shape_" + selection.shape()), Formatting.number(selection.centerX()), Formatting.number(selection.centerZ()), Formatting.radius(selection));
            TrimCommand.Task trimTask = new TrimCommand.Task();
            chunky.getTrimTasks().put(selection.world().getName(), trimTask);
            Optional<Path> regionPath = selection.world().getRegionDirectory();
            AtomicLong finishedRegions = new AtomicLong();
            AtomicLong deleted = new AtomicLong();
            long startTime = System.currentTimeMillis();
            AtomicLong updateTime = new AtomicLong(startTime);
            try {
                if (regionPath.isPresent()) {
                    try (final Stream<Path> files = Files.list(regionPath.get())) {
                        List<Path> regions = files
                                .filter(file -> ChunkCoordinate.fromRegionFile(file.getFileName().toString()).isPresent())
                                .toList();
                        long totalRegions = regions.size();
                        for (Path region : regions) {
                            if (trimTask.isCancelled()) {
                                break;
                            }
                            deleted.getAndAdd(checkRegion(selection.world(), region.getFileName().toString(), shape, inside, inhabitedTimeCheck, inhabitedTime));
                            finishedRegions.getAndIncrement();
                            if (!trimTask.isCancelled() && !chunky.getConfig().isSilent()) {
                                long currentTime = System.currentTimeMillis();
                                boolean updateIntervalElapsed = ((currentTime - updateTime.get()) / 1e3) > chunky.getConfig().getUpdateInterval();
                                if (updateIntervalElapsed || finishedRegions.get() == totalRegions) {
                                    chunky.getServer().getConsole().sendMessagePrefixed(TranslationKey.TASK_TRIM_UPDATE, selection.world().getName(), finishedRegions.get(), String.format("%.2f", 100f * finishedRegions.get() / totalRegions));
                                    updateTime.set(currentTime);
                                }
                            }
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            long totalTime = System.currentTimeMillis() - startTime;
            chunky.getTrimTasks().remove(selection.world().getName());
            sender.sendMessagePrefixed(TranslationKey.TASK_TRIM, deleted.get(), selection.world().getName(), String.format("%.3f", totalTime / 1e3f));
        });
        chunky.setPendingAction(sender, deletionAction);
        sender.sendMessagePrefixed(inside ? TranslationKey.FORMAT_TRIM_CONFIRM_INSIDE : TranslationKey.FORMAT_TRIM_CONFIRM, selection.world().getName(), translate("shape_" + selection.shape()), Formatting.number(selection.centerX()), Formatting.number(selection.centerZ()), Formatting.radius(selection), "/cs confirm");
        if (inhabitedTimeCheck) {
            sender.sendMessagePrefixed(TranslationKey.FORMAT_TRIM_CONFIRM_INHABITED, Formatting.number(inhabitedTime));
        }
    }

    private int checkRegion(World world, String regionFileName, Shape shape, boolean inside, boolean inhabitedTimeCheck, int inhabitedTime) {
        Optional<ChunkCoordinate> regionCoordinate = ChunkCoordinate.fromRegionFile(regionFileName);
        if (regionCoordinate.isEmpty()) {
            return 0;
        }
        int chunkX = regionCoordinate.get().x() << 5;
        int chunkZ = regionCoordinate.get().z() << 5;
        if (!inhabitedTimeCheck && shouldDeleteRegion(shape, inside, chunkX, chunkZ)) {
            return deleteRegion(world, regionFileName);
        } else {
            return trimRegion(world, regionFileName, shape, inside, chunkX, chunkZ, inhabitedTimeCheck, inhabitedTime);
        }
    }

    private boolean shouldDeleteRegion(Shape shape, boolean inside, int chunkX, int chunkZ) {
        for (int offsetX = 0; offsetX < 32; ++offsetX) {
            for (int offsetZ = 0; offsetZ < 32; ++offsetZ) {
                int chunkCenterX = ((chunkX + offsetX) << 4) + 8;
                int chunkCenterZ = ((chunkZ + offsetZ) << 4) + 8;
                if (inside != shape.isBounding(chunkCenterX, chunkCenterZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    private int deleteRegion(World world, String regionFileName) {
        try {
            Path regionPath = world.getRegionDirectory().map(region -> region.resolve(regionFileName)).orElseThrow(IllegalStateException::new);
            Files.deleteIfExists(regionPath);
            Path poiPath = world.getPOIDirectory().map(region -> region.resolve(regionFileName)).orElse(null);
            if (poiPath != null) {
                Files.deleteIfExists(poiPath);
            }
            Path entitiesPath = world.getEntitiesDirectory().map(region -> region.resolve(regionFileName)).orElse(null);
            if (entitiesPath != null) {
                Files.deleteIfExists(entitiesPath);
            }
            return 1024;
        } catch (IOException e) {
            e.printStackTrace();
            return 0;
        }
    }

    private int trimRegion(World world, String regionFileName, Shape shape, boolean inside, int chunkX, int chunkZ, boolean inhabitedTimeCheck, int inhabitedTime) {
        Path regionPath = world.getRegionDirectory().map(region -> region.resolve(regionFileName)).orElseThrow(IllegalStateException::new);
        Path poiPath = world.getPOIDirectory().map(region -> region.resolve(regionFileName)).orElse(null);
        Path entitiesPath = world.getEntitiesDirectory().map(region -> region.resolve(regionFileName)).orElse(null);
        int marked = 0;
        int deleted = 0;
        RegionFile regionData = inhabitedTimeCheck ? new RegionFile(regionPath.toFile(), ChunkFilter.of(TagType.LONG, "InhabitedTime")) : null;
        try (final RandomAccessFile regionFile = new RandomAccessFile(regionPath.toFile(), "rw");
             RandomAccessFile poiFile = poiPath == null || Files.notExists(poiPath) ? null : new RandomAccessFile(poiPath.toFile(), "rw");
             RandomAccessFile entitiesFile = entitiesPath == null || Files.notExists(entitiesPath) ? null : new RandomAccessFile(entitiesPath.toFile(), "rw")) {
            if (regionFile.length() < 4096) {
                return 0;
            }
            boolean poiValid = poiFile != null && poiFile.length() >= 4096;
            boolean entitiesValid = entitiesFile != null && entitiesFile.length() >= 4096;
            for (int offsetX = 0; offsetX < 32; ++offsetX) {
                for (int offsetZ = 0; offsetZ < 32; ++offsetZ) {
                    int offsetChunkX = chunkX + offsetX;
                    int offsetChunkZ = chunkZ + offsetZ;
                    int chunkCenterX = (offsetChunkX << 4) + 8;
                    int chunkCenterZ = (offsetChunkZ << 4) + 8;
                    boolean trimChunk;
                    if (inside) {
                        trimChunk = shape.isBounding(chunkCenterX, chunkCenterZ);
                    } else {
                        trimChunk = !shape.isBounding(chunkCenterX, chunkCenterZ);
                    }
                    boolean trimInhabited = regionData == null || regionData.getChunk(offsetChunkX, offsetChunkZ)
                            .map(chunk -> {
                                Tag tag = chunk.getData();
                                if (!(tag instanceof final LongTag inhabited)) {
                                    return true;
                                }
                                return inhabited.value() <= inhabitedTime;
                            })
                            .orElse(true);
                    if (trimChunk && trimInhabited) {
                        ++marked;
                        int chunkLocation = ((offsetX % 32) + (offsetZ % 32) * 32) * 4;
                        regionFile.seek(chunkLocation);
                        if (regionFile.readInt() != 0) {
                            regionFile.seek(chunkLocation);
                            regionFile.writeInt(0);
                            ++deleted;
                        }
                        if (poiValid) {
                            poiFile.seek(chunkLocation);
                            if (poiFile.readInt() != 0) {
                                poiFile.seek(chunkLocation);
                                poiFile.writeInt(0);
                            }
                        }
                        if (entitiesValid) {
                            entitiesFile.seek(chunkLocation);
                            if (entitiesFile.readInt() != 0) {
                                entitiesFile.seek(chunkLocation);
                                entitiesFile.writeInt(0);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (inhabitedTimeCheck && marked == 1024) {
            deleteRegion(world, regionFileName);
        }
        return deleted;
    }

    @Override
    public List<String> suggestions(CommandArguments arguments) {
        if (arguments.size() == 1) {
            List<String> suggestions = new ArrayList<>();
            chunky.getServer().getWorlds().forEach(world -> suggestions.add(world.getName()));
            return suggestions;
        } else if (arguments.size() == 2) {
            return ShapeType.all();
        }
        return List.of();
    }

    public static final class Task {
        private boolean cancelled;

        public boolean isCancelled() {
            return cancelled;
        }

        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }
    }
}
