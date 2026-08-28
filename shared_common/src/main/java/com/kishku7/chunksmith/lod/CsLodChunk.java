package com.kishku7.chunksmith.lod;

import java.util.List;

/**
 * <b>Why these fields and not others.</b> The format must satisfy the union of what voxy and Distant
 * Horizons need, and DH is the demanding one:
 * <ul>
 *   <li><b>Full block STATE strings</b>, not block ids -- DH has no fluid channel, so water IS a state;
 *       waterlogged / snow layers / stair shapes all matter.</li>
 *   <li><b>Sky light and block light kept separate</b> (voxy blends them into one byte; DH will not).</li>
 *   <li><b>Light for air voxels, all the way to the build ceiling</b> -- DH renders black LODs otherwise,
 *       which is why empty sections are still carried (they collapse to a few bytes).</li>
 * </ul>
 * Voxy needs a strict subset, and mips levels 1-4 itself on insert, so we only ever persist LOD-0.
 */
public final class CsLodChunk {

    public static final int BLOCKS_PER_SECTION = 16 * 16 * 16;

    public static final int BIOMES_PER_SECTION = 4 * 4 * 4;

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

    public int getMinSectionY() {
        return minSectionY;
    }

    public List<String> getBlockPalette() {
        return blockPalette;
    }

    public List<String> getBiomePalette() {
        return biomePalette;
    }

    public List<Section> getSections() {
        return sections;
    }

    /**
     * A uniform array is stored as a single palette index rather than 4096 (or 64) entries -- which is
     * what makes carrying light to the build ceiling affordable, since everything above the terrain is
     * uniform air with uniform sky light. Index order is YZX (y * 256 + z * 16 + x) for blocks and
     * (y * 16 + z * 4 + x) for biomes.
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

        public static int nibble(final byte[] packed, final int index) {
            final int b = packed[index >> 1] & 0xFF;
            return (index & 1) == 0 ? (b & 0x0F) : (b >> 4);
        }
    }
}
