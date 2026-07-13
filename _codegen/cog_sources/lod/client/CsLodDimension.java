package com.kishku7.chunksmith.lod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

/**
 * WHICH DIMENSION IS THE PLAYER IN, right now -- named exactly the way the server names its store.
 *
 * <p>This one string is the whole address of a CSLOD record. A region coordinate on its own is meaningless:
 * region (0,0) exists in the overworld, in the Nether and in the End, and they are three different places.
 * Every read, every write and every injection is scoped by this key -- {@code chunksmith/lod/&lt;server&gt;/
 * &lt;dimension&gt;/r.x.z.cslod} -- and getting it wrong does not fail, it succeeds against the wrong world.
 *
 * <p><b>It shipped wrong once.</b> Chunksmith 3.1.0-beta-2's client took the dimension from the FIRST entry
 * of the server's hello list and never looked at it again, so a player who walked through a Nether portal
 * kept pulling the OVERWORLD's records and the injector pushed them into the level the player was now in.
 * Overworld terrain -- grass, oceans -- rendered in the Nether sky, and every log line said success. The
 * client must ask the LEVEL, on every tick, and never remember an answer across a dimension change.
 *
 * <p>The value matches the server's {@code LodSupport.dimensionKey}: the dimension's resource id with
 * {@code :} and {@code /} replaced by {@code _}, e.g. {@code minecraft_overworld},
 * {@code minecraft_the_nether}. That is the directory name the server writes, the name it puts in the
 * region index, and the name the client stores under. One derivation, both sides.
 *
 * <p>SHARED SOURCE -- canonical location: _codegen/cog_sources/lod. Edit ONLY there; the per-cell copy
 * under gen/ is overwritten by cog-gen on every build.
 */
public final class CsLodDimension {

    private CsLodDimension() {
    }

    /**
     * The key for the level the player is in RIGHT NOW, or {@code ""} when no level is loaded (during a
     * dimension change there is a window where there is no level at all -- callers must treat "" as
     * "ask me again next tick", never as a dimension).
     */
    public static String current() {
        final Level level = Minecraft.getInstance().level;
        return level == null ? "" : of(level);
    }

    /** The key for a specific level. */
    public static String of(final Level level) {
        //[[[cog
        // import cog, compat
        // cog.outl("final String id = level.dimension().%s().toString();"
        //          % compat.dimension_identifier_call(mcver))
        //]]]
        //[[[end]]]
        return id.replace(':', '_').replace('/', '_');
    }
}
