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

package com.kishku7.chunksmith.lod;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import com.kishku7.chunksmith.lod.net.CsLodServerNet;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.commands.arguments.EntityArgument;
import java.io.IOException;

/**
 * Server-operator commands for the CSLOD store, under {@code /cs lod}.
 *
 * <ul>
 *   <li>{@code status}: where the store is, what is in it, and whether the backchannel is up.</li>
 *   <li>{@code token <player>}: mint a backchannel token by hand.</li>
 * </ul>
 *
 * <p><b>Everything else moved to {@code /csclient} at 4.0.0.</b> The old {@code /cslod} root mixed
 * these two with three operations that run on the CLIENT -- the settings relay and the two renderer
 * backfills -- and gated those three on server permissions they had no use for. The renderer engines
 * are client-side, so on a dedicated server the backfills could only ever report "not available".
 * They are client commands now, and work against the store the client downloaded.
 *
 * <p>Loader-blind: this class only builds the brigadier node. Each loader's entrypoint grafts it
 * onto the {@code /cs} root it is already registering, which is also where the permission gate
 * comes from.
 *
 * <p>Grafted rather than folded into the shared command tree: that tree lives in shared_common and
 * is wired to TranslationKey and the lang files, which the LOD feature still has no business
 * reaching into. The graft happens at the loader layer, where both trees are already in scope.
 */
public final class CsLodCommand {

    private CsLodCommand() {
    }

    /**
     * The {@code lod} node, grafted under the {@code /cs} root by each loader's entrypoint.
     *
     * <p>No permission predicate here. The {@code /cs} root already gates on
     * {@code chunksmith.command} (auto-true in single-player) and every child inherits it, so the
     * per-node {@code operatorOnly} this file used to carry was doing that job twice. Anything a
     * PLAYER runs against their own client lives on {@code /csclient}, ungated -- that split is the
     * whole point of the 4.0.0 restructure.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> buildServerNode() {
        LiteralArgumentBuilder<CommandSourceStack> lod = Commands.literal("lod");

        lod.then(Commands.literal("status").executes(context -> {
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            Path store = LodSupport.storeRoot(level);
            long bytes = sizeOf(store);
            // The record count, not just the byte size: the number an operator compares against their
            // chunk count to answer "does my store actually cover my world?". Header reads only (8 KB
            // per region, no record decode), so it is safe to run from a command.
            long records;
            try {
                records = CsLodPresenceIndex.countRecords(store);
            } catch (IOException e) {
                records = -1L;
            }

            // One call per line. An embedded newline renders literally in chat, which is why this was
            // a single packed line before; 3.18.1's startup banner hit the same wall and was fixed the
            // same way, one log call per line.
            say(source, "dimension:   " + LodSupport.dimensionKey(level));
            say(source, "world id:    " + orNone(LodSupport.worldId(level.getServer())));
            say(source, "store:       " + store);
            say(source, "exists:      " + Files.isDirectory(store));
            say(source, "records:     " + (records < 0 ? "unreadable" : Long.toString(records)));
            say(source, "size:        " + (bytes / 1024) + " KB");
            say(source, "decision:    " + LodSupport.describeDecision(level.getServer()));
            say(source, "backchannel: " + CsLodServerNet.describe());
            String rendererLine = renderers();
            if (!rendererLine.isEmpty()) {
                say(source, "renderers:   " + rendererLine);
            }
            return 1;
        }));

        lod.then(Commands.literal("token")
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> {
                            ServerPlayer target =
                                    EntityArgument.getPlayer(context, "player");
                            String token = CsLodServerNet.issueFor(target);
                            if (token == null) {
                                context.getSource().sendFailure(Component.literal(
                                        "[chunksmith] the LOD backchannel is not running"));
                                return 0;
                            }
                            //[[[cog
                            // import cog, compat
                            // cog.outl('context.getSource().sendSuccess(() -> Component.literal(')
                            // cog.outl('        "[chunksmith] token for " + target.getGameProfile().%s() + ": " + token), false);'
                            //          % compat.profile_name_call(mcver))
                            //]]]
                            //[[[end]]]
                            return 1;
                        })));

        return lod;
    }

    /** One status line. Not broadcast: a readout is for whoever asked, not the room. */
    private static void say(final CommandSourceStack source, final String line) {
        source.sendSuccess(() -> Component.literal("[chunksmith] " + line), false);
    }

    private static String orNone(final String value) {
        return value == null || value.isEmpty() ? "(none -- store not writable)" : value;
    }


    /**
     * Returns the renderer fields of the status line. A cell reports only the renderers
     * it can actually feed. Where voxy has no build the line says so, rather than "not
     * available" for something that could never be available.
     *
     * @return the renderer fields, ready to drop into the status line
     */
    private static String renderers() {
        //[[[cog
        // import cog, compat
        // parts = []
        // if compat.has_voxy(mcver, loader):
        //     parts.append('" | voxy: " + (CsLodVoxyInjector.voxyAvailable() ? "available" : "not available")')
        // elif compat.has_dh(mcver, loader):
        //     parts.append('" | voxy: no build for this loader/MC"')
        // if compat.has_dh(mcver, loader):
        //     parts.append('" | dh: " + dhStatus()')
        // if parts:
        //     cog.outl("return %s;" % ("\n        + ".join(parts)))
        // else:
        //     cog.outl('// No renderer exists for this cell -- the store is served, not injected.')
        //     cog.outl('return "";')
        //]]]
        //[[[end]]]
    }

    //[[[cog
    // import cog, compat
    // if compat.has_dh(mcver, loader):
    //     cog.outl('/** CsLodDhSupport hard-references DH types, so only touch it when DH is actually installed. */')
    //     cog.outl('private static String dhStatus() {')
    //     cog.outl('    if (!LodPlatform.isModLoaded("distanthorizons")) {')
    //     cog.outl('        return "not installed";')
    //     cog.outl('    }')
    //     cog.outl('    try {')
    //     cog.outl('        return CsLodDhSupport.describe();')
    //     cog.outl('    } catch (final LinkageError error) {')
    //     cog.outl('        return "incompatible";')
    //     cog.outl('    }')
    //     cog.outl('}')
    //]]]
    //[[[end]]]

    private static long sizeOf(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0L;
        }
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (Exception e) {
                    return 0L;
                }
            }).sum();
        } catch (Exception e) {
            return 0L;
        }
    }
}
