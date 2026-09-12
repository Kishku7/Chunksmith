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

package com.kishku7.chunksmith.lod.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

import com.kishku7.chunksmith.lod.client.net.CsLodClientNet;
import com.kishku7.chunksmith.lod.client.render.LodInjector;


/**
 * {@code /csclient} -- everything a player runs against their OWN client, at any permission level.
 *
 * <p>A real client command, not an ungated server one. That matters most when it is needed most: on a
 * vanilla server, or one with no Chunksmith, a server command does not exist at all, and "why am I
 * getting no LODs here" is exactly the question you want to ask in that situation.
 *
 * <p><b>Generic over the command source on purpose.</b> Fabric hands a client command
 * {@code CommandDispatcher<FabricClientCommandSource>}; NeoForge and Forge hand it
 * {@code CommandDispatcher<CommandSourceStack>}, the same type the server uses. There is no shared
 * supertype, so the node is built against an unconstrained {@code S} and each loader passes a
 * {@link Reply} that knows how to put a line on that loader's source. Nothing else in here needs to
 * know what a source is, because a command that reports on local state only ever needs to talk back.
 */
public final class CsLodClientCommand {

    /** How a loader puts one line in front of the player who typed the command. */
    @FunctionalInterface
    public interface Reply<S> {
        void line(S source, String text);
    }

    private CsLodClientCommand() {
    }

    /**
     * Builds the node under {@code name}. Registered twice per loader in practice: once as
     * {@code csclient}, and NOT as {@code cslod} -- see the {@code legacy} package for what happens
     * to that name instead.
     */
    public static <S> LiteralArgumentBuilder<S> build(final String name, final Reply<S> reply) {
        LiteralArgumentBuilder<S> root = LiteralArgumentBuilder.literal(name);

        root.then(LiteralArgumentBuilder.<S>literal("status").executes(context -> {
            S source = context.getSource();
            for (String line : CsLodClientNet.status()) {
                reply.line(source, "[chunksmith] " + line);
            }
            return 1;
        }));

        root.then(LiteralArgumentBuilder.<S>literal("reset").executes(context -> {
            S source = context.getSource();
            long removed = CsLodClientNet.resetAll();
            if (removed < 0L) {
                reply.line(source, "[chunksmith] reset: the store could not be read; nothing was removed");
                return 0;
            }
            reply.line(source, "[chunksmith] reset: removed " + removed
                    + " file(s); re-syncing from the server");
            // Said every time, not just when it matters, because the one case where it DOES matter is
            // the one where the player cannot tell: a world regenerated server-side still renders its
            // old terrain out of the renderer's own database, which is not ours to clear.
            reply.line(source, "[chunksmith] note: your renderer's own database was not touched. If the");
            reply.line(source, "[chunksmith]       server's world was regenerated, clear Distant Horizons");
            reply.line(source, "[chunksmith]       or Voxy separately.");
            return 1;
        }));

        root.then(settings(reply));

        //[[[cog
        // import cog, compat
        // if compat.has_voxy(mcver, loader):
        //     cog.outl('root.then(inject(reply, "inject_voxy", "Voxy"));')
        // else:
        //     cog.outl('// No inject_voxy on this cell: voxy is Fabric-only and upstream has never published a')
        //     cog.outl('// build for this (loader, MC), so there is nothing to compile against. The subcommand is')
        //     cog.outl('// compile-time ABSENT rather than present-and-refusing -- a command that can only ever')
        //     cog.outl('// fail is worse than one that is not offered.')
        // if compat.has_dh(mcver, loader):
        //     cog.outl('root.then(inject(reply, "inject_dh", "Distant Horizons"));')
        // else:
        //     cog.outl('// No inject_dh on this cell either.')
        //]]]
        //[[[end]]]

        return root;
    }

    /**
     * {@code set}, {@code set <name>}, {@code set <name> <value>}, against
     * {@code config/chunksmith-lod.properties}.
     *
     * <p>Reads and writes the file directly. Until 4.0.0 this was a SERVER command that relayed the
     * request to the client over the LOD channel and waited for the client to answer, because the
     * command lived on the wrong side; the file has always been here.
     */
    private static <S> LiteralArgumentBuilder<S> settings(final Reply<S> reply) {
        LiteralArgumentBuilder<S> set = LiteralArgumentBuilder.literal("set");

        set.executes(context -> {
            S source = context.getSource();
            reply.line(source, "[chunksmith] LOD client settings (config/" + CsLodClientConfig.FILE_NAME + "):");
            for (CsLodClientSettings.Setting setting : CsLodClientSettings.all()) {
                reply.line(source, "[chunksmith]   " + setting.name() + " = " + setting.read()
                        + "  -- " + setting.help());
            }
            return 1;
        });

        RequiredArgumentBuilder<S, String> named =
                RequiredArgumentBuilder.argument("name", StringArgumentType.word());
        named.suggests((context, builder) -> {
            for (String option : CsLodClientSettings.names()) {
                builder.suggest(option);
            }
            return builder.buildFuture();
        });
        named.executes(context -> show(context.getSource(), reply,
                StringArgumentType.getString(context, "name")));

        RequiredArgumentBuilder<S, String> valued =
                RequiredArgumentBuilder.argument("value", StringArgumentType.word());
        valued.suggests((context, builder) -> {
            // Completions come from the setting, so they cannot drift from it.
            var found = CsLodClientSettings.find(StringArgumentType.getString(context, "name"));
            if (found.isPresent()) {
                for (String option : found.get().kind().completions()) {
                    builder.suggest(option);
                }
            }
            return builder.buildFuture();
        });
        valued.executes(context -> write(context.getSource(), reply,
                StringArgumentType.getString(context, "name"),
                StringArgumentType.getString(context, "value")));

        named.then(valued);
        set.then(named);
        return set;
    }

    private static <S> int show(final S source, final Reply<S> reply, final String name) {
        var found = CsLodClientSettings.find(name);
        if (found.isEmpty()) {
            return unknown(source, reply, name);
        }
        CsLodClientSettings.Setting setting = found.get();
        reply.line(source, "[chunksmith] " + setting.name() + " = " + setting.read()
                + "  -- " + setting.help());
        return 1;
    }

    private static <S> int write(final S source, final Reply<S> reply,
                                 final String name, final String value) {
        var found = CsLodClientSettings.find(name);
        if (found.isEmpty()) {
            return unknown(source, reply, name);
        }
        CsLodClientSettings.Setting setting = found.get();
        // A refused value is a shape error: a word where a number belongs. An out-of-range value is
        // accepted and clamped, so the reply reports what was STORED, not what was typed.
        if (!setting.write(value)) {
            var expected = setting.kind().completions();
            reply.line(source, "[chunksmith] '" + value + "' is not a valid value for " + setting.name()
                    + (expected.isEmpty() ? " (expected a whole number)"
                            : " (expected one of: " + String.join(", ", expected) + ")"));
            return 0;
        }
        reply.line(source, "[chunksmith] " + setting.name() + " = " + setting.read()
                + ", applied now and saved to config/" + CsLodClientConfig.FILE_NAME);
        return 1;
    }

    private static <S> int unknown(final S source, final Reply<S> reply, final String name) {
        reply.line(source, "[chunksmith] no LOD client setting called '" + name + "'. Known: "
                + String.join(", ", CsLodClientSettings.names()));
        return 0;
    }

    //[[[cog
    // import cog, compat
    // if compat.has_voxy(mcver, loader) or compat.has_dh(mcver, loader):
    //     cog.outl('''/**
    //  * A renderer backfill. Two very different jobs behind one name, and the split is the connection:
    //  *
    //  * <ul>
    //  *   <li><b>Multiplayer</b> -- replays the store this client DOWNLOADED. That is new in 4.0.0; the
    //  *       3.x command was server-side, so on a dedicated server it addressed the SERVER's renderer,
    //  *       which no player ever reads (mod_support #27).</li>
    //  *   <li><b>Single-player</b> -- replays the world's own store, which is the original backfill: a
    //  *       world pregenerated before the renderer was installed gets its LODs after the fact.</li>
    //  * </ul>
    //  */''')
    //     cog.outl('private static <S> LiteralArgumentBuilder<S> inject(final Reply<S> reply,')
    //     cog.outl('                                                    final String name,')
    //     cog.outl('                                                    final String renderer) {')
    //     cog.outl('    return LiteralArgumentBuilder.<S>literal(name).executes(context -> {')
    //     cog.outl('        S source = context.getSource();')
    //     cog.outl('        if (!CsLodClientNet.reinjectNow()) {')
    //     cog.outl('            reply.line(source, "[chunksmith] nothing to replay: no dimension is active. Join a"')
    //     cog.outl('                    + " world first, or on a single-player world let the pregen run.");')
    //     cog.outl('            return 0;')
    //     cog.outl('        }')
    //     cog.outl('        reply.line(source, "[chunksmith] replaying the LOD store into " + renderer')
    //     cog.outl('                + " -- watch /csclient status for progress");')
    //     cog.outl('        reply.line(source, "[chunksmith] injected so far: " + LodInjector.describe());')
    //     cog.outl('        return 1;')
    //     cog.outl('    });')
    //     cog.outl('}')
    //]]]
    //[[[end]]]
}
