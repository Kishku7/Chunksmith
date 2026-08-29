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

package com.kishku7.chunksmith.command;

import com.kishku7.chunksmith.Chunksmith;
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.util.AutoPause;
import com.kishku7.chunksmith.util.ChunkResidency;
import com.kishku7.chunksmith.util.Debug;
import com.kishku7.chunksmith.util.TickBudget;
import com.kishku7.chunksmith.util.TicketLedger;
import com.kishku7.chunksmith.util.UnloadDiagnostics;

import java.util.List;
import java.util.Optional;

public class DebugCommand implements ChunksmithCommand {
    @SuppressWarnings("unused")
    private final Chunksmith chunky;

    public DebugCommand(Chunksmith chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(Sender sender, CommandArguments arguments) {
        Optional<String> arg = arguments.next();
        if (arg.isPresent()) {
            String value = arg.get().toLowerCase();
            if ("on".equals(value) || "true".equals(value) || "enable".equals(value)) {
                Debug.ENABLED = true;
            } else if ("off".equals(value) || "false".equals(value) || "disable".equals(value)) {
                Debug.ENABLED = false;
            } else {
                Debug.ENABLED = !Debug.ENABLED;
            }
        } else {
            Debug.ENABLED = !Debug.ENABLED;
        }
        if (Debug.ENABLED) {
            sender.sendMessagePrefixed("Debug logging ENABLED. While a generation task runs, per-dimension entity-manager stats print to the server log every 5s. Run /cs debug again to turn it off.");
        } else {
            sender.sendMessagePrefixed("Debug logging disabled.");
        }
        sender.sendMessagePrefixed("Tick budget: " + TickBudget.describe());
        sender.sendMessagePrefixed("Auto-pause: " + AutoPause.describe());
        sender.sendMessagePrefixed("Chunk residency: " + ChunkResidency.describe());
        sender.sendMessagePrefixed("Chunk unloading: " + UnloadDiagnostics.describe());
        sender.sendMessagePrefixed("Chunksmith tickets: " + TicketLedger.describe());
        sender.sendMessagePrefixed("Chunk ticket levels: " + UnloadDiagnostics.describeLevels());
        sender.sendMessagePrefixed("Tickets by type: " + UnloadDiagnostics.describeTicketTally());
        sender.sendMessagePrefixed("Who holds them: " + UnloadDiagnostics.describeTicketSample());
    }

    @Override
    public List<String> suggestions(CommandArguments arguments) {
        return arguments.size() == 1 ? List.of("on", "off") : List.of();
    }
}