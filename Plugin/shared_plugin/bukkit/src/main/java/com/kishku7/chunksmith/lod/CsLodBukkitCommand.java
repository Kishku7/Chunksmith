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

import com.kishku7.chunksmith.platform.Sender;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code /cs lod} on the Bukkit plugin: the same operator commands the mod grafts onto its own
 * {@code /cs} root.
 *
 * <p><b>Handled here rather than in the shared command map</b>, for the same reason the mod grafts at
 * the loader layer: that map is wired to {@code TranslationKey} and the lang files, and the LOD
 * feature has no business reaching into them. The plugin's dispatcher checks for {@code lod} before
 * the map lookup, which is the Bukkit equivalent of brigadier preferring a literal child.
 *
 * <p><b>There is no {@code /csclient} here and there never will be.</b> That command runs on a
 * client, and a Bukkit server does not have one. The plugin serves the store; the connecting client
 * manages its own copy.
 *
 * <p><b>Status is per WORLD, plural.</b> A mod server is one save folder with the dimensions nested
 * inside it, so it has one store; Bukkit gives each world its own folder and therefore its own store.
 * Reporting them all also means the command answers usefully from the console, which has no world of
 * its own to report on.
 */
public final class CsLodBukkitCommand {

    /** The literal this intercepts, checked before the shared command map. */
    public static final String LITERAL = "lod";

    private CsLodBukkitCommand() {
    }

    /**
     * Runs a {@code /cs lod ...} invocation.
     *
     * @param rest everything after {@code lod}
     */
    public static void execute(final Sender sender, final String[] rest) {
        if (rest.length > 0 && "token".equalsIgnoreCase(rest[0])) {
            token(sender, rest);
            return;
        }
        status(sender);
    }

    public static List<String> suggestions(final String[] rest) {
        if (rest.length <= 1) {
            return List.of("status", "token");
        }
        if ("token".equalsIgnoreCase(rest[0])) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                names.add(player.getName());
            }
            return names;
        }
        return List.of();
    }

    private static void status(final Sender sender) {
        // One line per value. The mod's node does the same, and for the same reason: a packed line is
        // unreadable in chat and an embedded newline renders literally.
        List<World> worlds = Bukkit.getWorlds();
        say(sender, "world id:    " + orNone(worlds.isEmpty()
                ? "" : CsLodWorldId.forStore(LodSupport.storeRootBase(worlds.get(0)))));
        say(sender, "backchannel: " + CsLodServerBukkit.describe());

        for (World world : worlds) {
            Path store = LodSupport.storeRoot(world);
            long bytes = sizeOf(store);
            say(sender, LodSupport.dimensionKey(world) + ": "
                    + (Files.isDirectory(store) ? (bytes / 1024L) + " KB" : "no store")
                    + "  " + store);
        }
    }

    private static void token(final Sender sender, final String[] rest) {
        if (rest.length < 2) {
            say(sender, "usage: /cs lod token <player>");
            return;
        }
        Player target = Bukkit.getPlayerExact(rest[1]);
        if (target == null) {
            say(sender, "no player online called '" + rest[1] + "'");
            return;
        }
        String token = CsLodServerBukkit.issueFor(target);
        if (token == null) {
            say(sender, "the LOD backchannel is not running");
            return;
        }
        say(sender, "token for " + target.getName() + ": " + token);
    }

    private static void say(final Sender sender, final String line) {
        sender.sendMessage("[chunksmith] " + line);
    }

    private static String orNone(final String value) {
        return value == null || value.isEmpty() ? "(none -- store not writable)" : value;
    }

    private static long sizeOf(final Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0L;
        }
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }
}
