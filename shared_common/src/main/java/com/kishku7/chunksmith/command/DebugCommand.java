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
        final Optional<String> arg = arguments.next();
        if (arg.isPresent()) {
            final String value = arg.get().toLowerCase();
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