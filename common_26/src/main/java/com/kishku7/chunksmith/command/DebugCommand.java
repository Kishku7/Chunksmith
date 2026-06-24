package com.kishku7.chunksmith.command;

import com.kishku7.chunksmith.Chunky;
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.util.Debug;

import java.util.List;
import java.util.Optional;

/**
 * {@code /cs debug [on|off]} - toggles Chunksmith's on-demand entity-manager diagnostic logging.
 * With no argument it flips the current state. While enabled and a generation task is running, each
 * dimension's entity-manager prints a stats line to the server log every ~5 seconds
 * (known/visible/sections/loadStatuses/visibility/inbox/toUnload, plus disk-read fast-path hits).
 * Default off, so a normal install logs nothing.
 */
public class DebugCommand implements ChunkyCommand {
    @SuppressWarnings("unused")
    private final Chunky chunky;

    public DebugCommand(final Chunky chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(final Sender sender, final CommandArguments arguments) {
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
    }

    @Override
    public List<String> suggestions(final CommandArguments arguments) {
        return arguments.size() == 1 ? List.of("on", "off") : List.of();
    }
}