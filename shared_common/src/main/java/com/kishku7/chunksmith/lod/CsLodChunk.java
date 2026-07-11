package com.kishku7.chunksmith.lod;

import java.util.List;

/**
 * One chunk of CSLOD data: the neutral, mod-independent LOD source ChunkSmith emits during pregen.
 *
 * <p>Deliberately MC-free -- plain arrays and strings -- so shared code, the codec, the region store
 * and (later) the wire protocol all speak the same thing, and so the format is not hostage to any
 * consumer's internals.
 *
 * <p><b>Why these fields and not others.</b> The format must satisfy the UNION of what voxy and
 * Distant Horizons need. DH is the demanding one:
 * <ul>
 *   <li><b>Full block STATE strings</b>, not block ids -- DH has no fluid channel, so water IS a
 *       state; waterlogged / snow layers / stair shapes all matter.</li>
 *   <li><b>Sky light and block light kept SEPARATE</b> (voxy blends them into one byte; DH will not).</li>
 *   <li><b>Light for AIR voxels, all the way to the build ceiling</b> -- DH renders black LODs
 *       otherwise. This is why empty sections are still carried (they collapse to a few bytes).</li>
 *   <li><b>Absolute section Y</b>, gap-free columns.</li>
 * </ul>
 * Voxy needs a strict subset, and mips levels 1-4 itself on insert, so we only ever persist LOD-0.
 *
 * <p>Block indices are per-voxel (4096 per section). Biome indices are 4x4x4 (64 per section) --
 * that is exactly how Minecraft stores them, so it is lossless, and consumers expand as needed.
 */
public final class CsLodChunk {

    /** Voxels per 16x16x16 section. */
    public static final int BLOCKS_PER_SECTION = 16 * 16 * 16;

    /** Biome cells per section: Minecraft stores biomes at 4x4x4 granularity. */
    public static final int BIOMES_PER_SECTION = 4 * 4 * 4;

    /** Bytes in a nibble-per-voxel light array (4096 voxels / 2 per byte). */
    public static final int LIGHT_BYTES = BLOCKS_PER_SECTION / 2;

    private final String dimension;
    private final int chunkX;
    private final int chunkZ;
    private final int minSectionY;
    private final List<String> blockPalette;
    private final List<String> biomePalette;
    private final List<Section> sections;

    public CsLodChunk(final String dimension,
                      final int chunkX,
                      final int chunkZ,
                      final int minSectionY,
                      final List<String> blockPalette,
                      final List<String> biomePalette,
                      final List<Section> sections) {
        this.dimension = dimension;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minSectionY = minSectionY;
        this.blockPalette = blockPalette;
        this.biomePalette = biomePalette;
        this.sections = sections;
    }

    public String getDimension() {
        return dimension;
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    /** Absolute section index of the lowest section (level min build height / 16). */
    public int getMinSectionY() {
        return minSectionY;
    }

    /** Block STATE strings, e.g. {@code minecraft:oak_stairs[facing=east,waterlogged=true]}. */
    public List<String> getBlockPalette() {
        return blockPalette;
    }

    /** Biome ids, e.g. {@code minecraft:plains}. */
    public List<String> getBiomePalette() {
        return biomePalette;
    }

    /** Sections, bottom-up, starting at {@link #getMinSectionY()}. Gap-free. */
    public List<Section> getSections() {
        return sections;
    }

    /**
     * One 16x16x16 section.
     *
     * <p>A uniform array is stored as a single palette index rather than 4096 (or 64) entries --
     * which is what makes carrying light all the way to the build ceiling affordable, since
     * everything above the terrain is uniform air with uniform sky light.
     *
     * <p>Index order is YZX (y * 256 + z * 16 + x) for blocks and (y * 16 + z * 4 + x) for biomes.
     */
    public static final class Section {

        private final int[] blocks;      // 4096 palette indices, or null when uniformBlock >= 0
        private final int uniformBlock;  // palette index, or -1
        private final int[] biomes;      // 64 palette indices, or null when uniformBiome >= 0
        private final int uniformBiome;  // palette index, or -1
        private final byte[] skyLight;   // 2048 nibble-packed bytes, or null when uniformSky >= 0
        private final int uniformSky;    // 0-15, or -1
        private final byte[] blockLight; // 2048 nibble-packed bytes, or null when uniformBlockLight >= 0
        private final int uniformBlockLight; // 0-15, or -1

        public Section(final int[] blocks,
                       final int uniformBlock,
                       final int[] biomes,
                       final int uniformBiome,
                       final byte[] skyLight,
                       final int uniformSky,
                       final byte[] blockLight,
                       final int uniformBlockLight) {
            this.blocks = blocks;
            this.uniformBlock = uniformBlock;
            this.biomes = biomes;
            this.uniformBiome = uniformBiome;
            this.skyLight = skyLight;
            this.uniformSky = uniformSky;
            this.blockLight = blockLight;
            this.uniformBlockLight = uniformBlockLight;
        }

        public int[] getBlocks() {
            return blocks;
        }

        public int getUniformBlock() {
            return uniformBlock;
        }

        public int[] getBiomes() {
            return biomes;
        }

        public int getUniformBiome() {
            return uniformBiome;
        }

        public byte[] getSkyLight() {
            return skyLight;
        }

        public int getUniformSky() {
            return uniformSky;
        }

        public byte[] getBlockLight() {
            return blockLight;
        }

        public int getUniformBlockLight() {
            return uniformBlockLight;
        }

        /** Read a nibble out of a 2048-byte packed light array. */
        public static int nibble(final byte[] packed, final int index) {
            final int b = packed[index >> 1] & 0xFF;
            return (index & 1) == 0 ? (b & 0x0F) : (b >> 4);
        }
    }
}
