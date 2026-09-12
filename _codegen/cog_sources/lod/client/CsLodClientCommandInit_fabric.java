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

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.network.chat.Component;

/**
 * Registers {@code /csclient} on Fabric.
 *
 * <p>The side guard is the LOADER's: this class is a {@code "client"} entrypoint in
 * {@code fabric.mod.json}, so a dedicated server never loads it. cog-gen cross-checks the gate
 * against that manifest and throws on drift, because the manifest is a static per-cell resource and
 * a mismatch is invisible at build time and fatal at client load.
 *
 * <p><b>The node is built from brigadier directly, not from Fabric's builder factory.</b>
 * {@code ClientCommandManager} was renamed to {@code ClientCommands} at command-api-v2 3.x (the 26
 * cell), and {@code FabricClientCommandSource.getWorld()} became {@code getLevel()} in the same step.
 * {@link CsLodClientCommand} touches neither: {@code LiteralArgumentBuilder.literal} is source-agnostic
 * and the command never asks its source for a world. So this cell needs no Cog seam for either rename,
 * which is the whole reason the node is generic.
 */
public final class CsLodClientCommandInit implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) ->
                dispatcher.register(CsLodClientCommand.build("csclient",
                        (source, line) -> source.sendFeedback(Component.literal(line)))));
    }
}
