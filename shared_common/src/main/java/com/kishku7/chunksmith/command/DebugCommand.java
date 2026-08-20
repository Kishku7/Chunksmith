package com.kishku7.chunksmith.command;

import com.kishku7.chunksmith.Chunksmith;
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.util.ChunkResidency;
import com.kishku7.chunksmith.util.Debug;

import java.util.List;
import java.util.Optional;

/**
 * {@code /cs debug [on|off]} - toggles Chunksmith's on-demand entity-manager diagnostic logging.
 * With no argument it flips the current state. While enabled and a generation task is running, each
 * dimension's entity-manager prints a stats line to the server log every ~5 seconds
 * (known/visible/sections/loadStatuses/visibility/inbox/toUnload, plus disk-read fast-path hits).
 * Default off, so a normal install logs nothing.
 *
 * <p>It ALSO prints the chunk-residency snapshot every time it is run, whichever way the toggle went
 * -- resident count, the run's baseline, how many this run added, whether a drain is in progress, and
 * how the last drain ended. That is the readout 3.5.1 was missing: without it the only way to learn
 * what the server was holding was to set a low throttleMaxAddedChunks, start a run and read the
 * backpressure line, which perturbs the very thing being measured and cannot be done at all while the
 * server is idle.
 */
public class DebugCommand implements ChunksmithCommand {
    @SuppressWarnings("unused")
    private final Chunksmith chunky;

    public DebugCommand(final Chunksmith chunky) {
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
        sender.sendMessagePrefixed("Chunk residency: " + ChunkResidency.describe());
    }

    @Override
    public List<String> suggestions(final CommandArguments arguments) {
        return arguments.size() == 1 ? List.of("on", "off") : List.of();
    }
}