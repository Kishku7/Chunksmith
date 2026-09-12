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

package com.kishku7.chunksmith.lod.legacy;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import com.kishku7.chunksmith.lod.net.CsLodServerNet;

/**
 * COMPATIBILITY ONLY. The {@code /cslod} name, kept alive on a 4.x server purely to tell a 3.x client
 * why its commands vanished.
 *
 * <p>Delete this package when 3.x clients stop mattering; nothing in the feature code reaches into it.
 *
 * <p><b>Who sees it, and why the gate is two-part.</b> The node is hidden unless the player's client
 * GREETED us AND spoke a protocol older than ours. Brigadier sends a per-player command tree and drops
 * nodes whose {@code requires} fails, so on a 4.x client {@code /cslod} is genuinely ABSENT rather
 * than present-and-scolding. The greeting half matters just as much: a vanilla client never greets,
 * and telling somebody who has no Chunksmith at all to update their Chunksmith is worse than silence.
 *
 * <p><b>What it deliberately does NOT do.</b> It does not forward to the new commands. A 3.x client
 * cannot run {@code /csclient} -- that is a CLIENT command and their client does not have it -- so
 * proxying would produce a reply they could not act on. Saying plainly that the client is out of date
 * is the only answer that helps.
 *
 * <p>The other direction needs nothing at all: a 4.x client registers no {@code /cslod} of its own, so
 * against a 3.x server that server's real {@code /cslod} arrives in the command tree and works, and
 * nothing here is involved.
 */
public final class CsLodLegacyCommand {

    private CsLodLegacyCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("cslod")
                .requires(CsLodLegacyCommand::outOfDateClient);

        root.executes(context -> tell(context.getSource()));

        // Swallow whatever they typed after it. Without this, `/cslod status` is a brigadier parse
        // error -- "Unknown or incomplete command" -- and the message explaining why never prints,
        // which is the entire point of keeping the name.
        RequiredArgumentBuilder<CommandSourceStack, String> rest =
                RequiredArgumentBuilder.argument("rest", StringArgumentType.greedyString());
        rest.executes(context -> tell(context.getSource()));
        root.then(rest);

        return root;
    }

    private static boolean outOfDateClient(final CommandSourceStack source) {
        return source.getEntity() instanceof ServerPlayer player
                && CsLodServerNet.isLegacyClient(player);
    }

    private static int tell(final CommandSourceStack source) {
        source.sendFailure(Component.literal(
                "[chunksmith] /cslod is gone in Chunksmith 4. Update your Chunksmith client:"
                        + " the LOD commands you want are /csclient, and they run on your machine."));
        return 0;
    }
}
