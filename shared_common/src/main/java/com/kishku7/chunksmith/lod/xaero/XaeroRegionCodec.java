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

import com.kishku7.chunksmith.lod.xaero.XaeroTile.Overlay;
import com.kishku7.chunksmith.lod.xaero.XaeroTile.XaeroPixel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Reads and writes the Xaero's World Map region file, {@code <rx>_<rz>.zip}, format 8.8 as written by World Map
 * 1.47.2: a zip holding one entry, {@code region.xaero}, big-endian. A region is 512x512 blocks, 8x8 "tile chunks"
 * of 4x4 tiles, a tile being one 16x16 chunk, which is the same footprint as a CSLOD region.
 *
 * <pre>
 * u8 255, i32 version (major &lt;&lt; 16 | minor)
 * per present tile chunk: u8 (o &lt;&lt; 4 | p), then 16 tiles (i outer, j inner), each i32 -1 (absent) or
 *   256 pixels (x outer, z inner), u8 worldInterpretationVersion, i32 caveStart, u8 caveDepth
 * </pre>
 * Pixels and the palettes are described on {@link #writePixel}. The block-state palette is shared by pixels and
 * overlays, grown in write order, and the biome palette is separate; both are per file, so a region is always
 * decoded and encoded whole. Verified against regions World Map itself wrote: re-encoding them reproduces the
 * bytes exactly.
 */
public final class XaeroRegionCodec {

    /** {@code (major << 16) | minor}: what World Map 1.47.2 writes. It loads files up to major 8 minor 8. */
    public static final int FULL_VERSION = (8 << 16) | 8;

    /** Xaero's marker for a tile written for the surface layer rather than a cave layer. */
    public static final int SURFACE_CAVE_START = Integer.MAX_VALUE;

    /** World Map's default cave-mode depth. Stored per tile and compared on rewrite, so it should match the client's. */
    public static final int DEFAULT_CAVE_DEPTH = 30;

    public static final int WORLD_INTERPRETATION_VERSION = 1;

    /** Tiles per region side. */
    public static final int TILES = 32;

    private static final int MAX_STATES = 1 << 20;

    private XaeroRegionCodec() {
    }

    /** Index of tile (lx, lz), both 0..31 region-relative chunk coordinates, in a region's tile array. */
    public static int tileIndex(final int lx, final int lz) {
        return lx * TILES + lz;
    }

    // ---- unzip / zip ------------------------------------------------------------------------------------

    /** Extracts {@code region.xaero} from a region zip. */
    public static byte[] unzip(final byte[] zip) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.getName().equals("region.xaero")) {
                    return in.readAllBytes();
                }
            }
        }
        throw new IOException("region zip has no region.xaero entry");
    }

    /** Wraps region bytes in the one-entry zip World Map expects. */
    public static byte[] zip(final byte[] xaeroBytes) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(xaeroBytes.length / 2 + 64);
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.setLevel(Deflater.DEFAULT_COMPRESSION);
            zip.putNextEntry(new ZipEntry("region.xaero"));
            zip.write(xaeroBytes);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    // ---- decode -----------------------------------------------------------------------------------------

    /**
     * Decodes region bytes into 1024 tile slots (null where absent). Throws {@link IOException} for anything that
     * is not a version-8 file this codec writes exactly, so a caller falls back to leaving the file alone rather
     * than merging into data it cannot reproduce.
     */
    public static XaeroTile[] decode(final byte[] xaeroBytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(xaeroBytes));
        if (in.readUnsignedByte() != 255) {
            throw new IOException("not a Xaero region file: bad marker");
        }
        int version = in.readInt();
        int major = version >>> 16;
        int minor = version & 0xFFFF;
        if (major != 8 || minor < 8 || minor > 8) {
            throw new IOException("unsupported Xaero region version " + major + "." + minor);
        }
        List<String> states = new ArrayList<>();
        List<String> biomes = new ArrayList<>();
        XaeroTile[] tiles = new XaeroTile[TILES * TILES];
        while (true) {
            int tileChunk;
            try {
                tileChunk = in.readUnsignedByte();
            } catch (EOFException end) {
                break;
            }
            int o = tileChunk >> 4;
            int p = tileChunk & 15;
            if (o > 7 || p > 7) {
                throw new IOException("bad tile chunk index " + tileChunk);
            }
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    in.mark(4);
                    if (in.readInt() == -1) {
                        continue;
                    }
                    in.reset();
                    int index = tileIndex(o * 4 + i, p * 4 + j);
                    if (tiles[index] != null) {
                        throw new IOException("tile saved twice");
                    }
                    tiles[index] = readTile(in, states, biomes);
                }
            }
        }
        return tiles;
    }

    private static XaeroTile readTile(final DataInputStream in, final List<String> states, final List<String> biomes)
            throws IOException {
        XaeroTile tile = new XaeroTile();
        for (int x = 0; x < XaeroTile.SIZE; x++) {
            for (int z = 0; z < XaeroTile.SIZE; z++) {
                tile.pixels[x * XaeroTile.SIZE + z] = readPixel(in, states, biomes);
            }
        }
        tile.worldInterpretationVersion = in.readUnsignedByte();
        tile.caveStart = in.readInt();
        tile.caveDepth = in.readUnsignedByte();
        return tile;
    }

    private static XaeroPixel readPixel(final DataInputStream in, final List<String> states, final List<String> biomes)
            throws IOException {
        int params = in.readInt();
        String state;
        if ((params & 1) != 0) {
            if ((params & (1 << 21)) != 0) {
                state = readStateNbt(in);
                addBounded(states, state);
            } else {
                state = states.get(in.readInt());
            }
        } else {
            state = "minecraft:grass_block";
        }
        if ((params & 64) != 0) {
            throw new IOException("legacy height byte not supported");
        }
        int height = ((params >> 12) & 0xFF) | (((params >> 25) & 15) << 8);
        height = (height << 20) >> 20;
        int topHeight = height;
        if ((params & (1 << 24)) != 0) {
            topHeight = in.readUnsignedByte();
        }
        List<Overlay> overlays = new ArrayList<>(0);
        if ((params & 2) != 0) {
            int count = in.readUnsignedByte();
            for (int k = 0; k < count; k++) {
                int op = in.readInt();
                String ostate;
                if ((op & 1) != 0) {
                    if ((op & 1024) != 0) {
                        ostate = readStateNbt(in);
                        addBounded(states, ostate);
                    } else {
                        ostate = states.get(in.readInt());
                    }
                } else {
                    ostate = "minecraft:water";
                }
                overlays.add(new Overlay(ostate, (op >> 11) & 15, (op >> 4) & 15));
            }
        }
        String biome = null;
        if ((params & (1 << 20)) != 0) {
            if ((params & (1 << 22)) != 0) {
                if ((params & (1 << 23)) != 0) {
                    throw new IOException("integer biome ids not supported");
                }
                biome = in.readUTF();
                addBounded(biomes, biome);
            } else {
                biome = biomes.get(in.readInt());
            }
        }
        return new XaeroPixel(state, height, topHeight, (params >> 8) & 15, biome, overlays);
    }

    private static void addBounded(final List<String> list, final String value) throws IOException {
        if (list.size() >= MAX_STATES) {
            throw new IOException("palette too large");
        }
        list.add(value);
    }

    // ---- encode -----------------------------------------------------------------------------------------

    /** Encodes up to 1024 tile slots (null = absent) into region bytes. */
    public static byte[] encode(final XaeroTile[] tiles) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 16);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(255);
        out.writeInt(FULL_VERSION);
        Map<String, Integer> states = new HashMap<>();
        Map<String, Integer> biomes = new HashMap<>();
        for (int o = 0; o < 8; o++) {
            for (int p = 0; p < 8; p++) {
                boolean any = false;
                for (int i = 0; i < 4 && !any; i++) {
                    for (int j = 0; j < 4; j++) {
                        if (tiles[tileIndex(o * 4 + i, p * 4 + j)] != null) {
                            any = true;
                            break;
                        }
                    }
                }
                if (!any) {
                    continue;
                }
                out.writeByte((o << 4) | p);
                for (int i = 0; i < 4; i++) {
                    for (int j = 0; j < 4; j++) {
                        XaeroTile tile = tiles[tileIndex(o * 4 + i, p * 4 + j)];
                        if (tile == null) {
                            out.writeInt(-1);
                            continue;
                        }
                        for (int x = 0; x < XaeroTile.SIZE; x++) {
                            for (int z = 0; z < XaeroTile.SIZE; z++) {
                                writePixel(out, tile.pixels[x * XaeroTile.SIZE + z], states, biomes);
                            }
                        }
                        out.writeByte(tile.worldInterpretationVersion);
                        out.writeInt(tile.caveStart);
                        out.writeByte(tile.caveDepth);
                    }
                }
            }
        }
        out.flush();
        return bytes.toByteArray();
    }

    /**
     * One pixel: {@code i32 params}, then in order the block state (palette index, or the NBT when new), the top
     * height byte when it differs from the height, the overlays, and the biome (palette index, or the UTF string
     * when new). Params bits: 0 not-grass, 1 has overlays, 8-11 light, 12-19 and 25-28 the 12-bit height, 20 biome
     * present, 21 state is new to the palette, 22 biome is new, 24 top height differs.
     */
    private static void writePixel(final DataOutputStream out, final XaeroPixel pixel,
                                   final Map<String, Integer> states, final Map<String, Integer> biomes)
            throws IOException {
        boolean grass = pixel.isGrass();
        int params = grass ? 0 : 1;
        params |= pixel.overlays.isEmpty() ? 0 : 2;
        params |= (pixel.light & 15) << 8;
        params |= (pixel.height & 0xFF) << 12;
        params |= pixel.biome != null ? 1048576 : 0;
        boolean topDiffers = pixel.height != pixel.topHeight;
        params |= topDiffers ? 16777216 : 0;
        params |= ((pixel.height >> 8) & 15) << 25;
        Integer stateIndex = null;
        if (!grass) {
            stateIndex = states.get(pixel.state);
            if (stateIndex == null) {
                params |= 2097152;
            }
        }
        Integer biomeIndex = null;
        if (pixel.biome != null) {
            biomeIndex = biomes.get(pixel.biome);
            if (biomeIndex == null) {
                params |= 4194304;
            }
        }
        out.writeInt(params);
        if (!grass) {
            if (stateIndex != null) {
                out.writeInt(stateIndex);
            } else {
                writeStateNbt(out, pixel.state);
                states.put(pixel.state, states.size());
            }
        }
        if (topDiffers) {
            out.writeByte(pixel.topHeight & 0xFF);
        }
        if (!pixel.overlays.isEmpty()) {
            out.writeByte(pixel.overlays.size());
            for (Overlay overlay : pixel.overlays) {
                boolean water = overlay.isWater();
                int op = water ? 0 : 1;
                op |= (overlay.light & 15) << 4;
                op |= (overlay.opacity & 15) << 11;
                Integer index = null;
                if (!water) {
                    index = states.get(overlay.state);
                    if (index == null) {
                        op |= 1024;
                    }
                }
                out.writeInt(op);
                if (!water) {
                    if (index != null) {
                        out.writeInt(index);
                    } else {
                        writeStateNbt(out, overlay.state);
                        states.put(overlay.state, states.size());
                    }
                }
            }
        }
        if (pixel.biome != null) {
            if (biomeIndex != null) {
                out.writeInt(biomeIndex);
            } else {
                out.writeUTF(pixel.biome);
                biomes.put(pixel.biome, biomes.size());
            }
        }
    }

    // ---- block-state NBT --------------------------------------------------------------------------------

    /** The id part of a state string: everything before {@code [}. */
    public static String idOf(final String state) {
        int bracket = state.indexOf('[');
        return bracket < 0 ? state : state.substring(0, bracket);
    }

    /** Raw {@code NbtIo.write} of {@code {id:"...", properties:{k:"v",...}}}, root unnamed. */
    static void writeStateNbt(final DataOutputStream out, final String state) throws IOException {
        out.writeByte(10);
        out.writeUTF("");
        out.writeByte(8);
        out.writeUTF("id");
        out.writeUTF(idOf(state));
        int bracket = state.indexOf('[');
        if (bracket >= 0 && state.endsWith("]")) {
            String body = state.substring(bracket + 1, state.length() - 1);
            if (!body.isEmpty()) {
                out.writeByte(10);
                out.writeUTF("properties");
                for (String pair : body.split(",")) {
                    int eq = pair.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    out.writeByte(8);
                    out.writeUTF(pair.substring(0, eq));
                    out.writeUTF(pair.substring(eq + 1));
                }
                out.writeByte(0);
            }
        }
        out.writeByte(0);
    }

    /** Reads a block-state compound back into a state string, keeping property order as stored. */
    static String readStateNbt(final DataInputStream in) throws IOException {
        if (in.readUnsignedByte() != 10) {
            throw new IOException("block state NBT root is not a compound");
        }
        in.readUTF();
        String id = null;
        Map<String, String> props = new LinkedHashMap<>();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0) {
                break;
            }
            String key = in.readUTF();
            if (type == 8 && key.equals("id")) {
                id = in.readUTF();
            } else if (type == 10 && key.equals("properties")) {
                while (true) {
                    int pt = in.readUnsignedByte();
                    if (pt == 0) {
                        break;
                    }
                    String pk = in.readUTF();
                    if (pt != 8) {
                        throw new IOException("non-string block state property");
                    }
                    props.put(pk, in.readUTF());
                }
            } else {
                throw new IOException("unexpected tag " + key + " in block state NBT");
            }
        }
        if (id == null) {
            throw new IOException("block state NBT has no id");
        }
        if (props.isEmpty()) {
            return id;
        }
        StringBuilder sb = new StringBuilder(id).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : props.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.append(']').toString();
    }
}
