/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) 2025-2026 Kishku7
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
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.util.TranslationKey;

/**
 * Puts a player's name on the server console when they run a command that changes what the server is doing.
 *
 * <p>A command's reply goes to whoever typed it, so when a player starts, pauses or cancels a pregen the
 * console otherwise shows the task changing state with nobody attached to it. A command typed at the
 * console needs no line: the console already saw it.
 */
final class CommandAudit {

    private CommandAudit() {
    }

    /**
     * @param action what the player did, in the past tense: {@code started}, {@code paused}, ...
     * @param what   what it was done to: a world name, or a setting and its new value
     */
    static void note(Chunksmith chunky, Sender sender, String action, String what) {
        if (sender == null || !sender.isPlayer()) {
            return;
        }
        chunky.getServer().getConsole().sendMessagePrefixed(TranslationKey.COMMAND_BY_PLAYER,
                sender.getName(), action, what);
    }
}
