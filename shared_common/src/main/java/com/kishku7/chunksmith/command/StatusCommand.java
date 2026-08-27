package com.kishku7.chunksmith.command;

import com.kishku7.chunksmith.Chunksmith;
import com.kishku7.chunksmith.GenerationTask;
import com.kishku7.chunksmith.lod.net.CsLodControl;
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.platform.World;
import com.kishku7.chunksmith.util.TranslationKey;

import java.util.List;
import java.util.Map;

/**
 * {@code /cs status} -- what is Chunksmith doing right now.
 *
 * <p>The one command to type when something looks wrong. {@code /cs progress} answers "how far
 * along is the run", which is only useful once you already know a run exists; this answers the
 * question before that one, and it answers it whether or not anything is running.
 *
 * <p>It reports the pre-gen state and the LOD backchannel together on purpose. Those are the two
 * halves of the mod, and the questions operators actually arrive with -- "is it generating?" and
 * "why are my players getting no LOD?" -- were previously answered by two different commands, one
 * of which ({@code /cslod status}) is not obvious from {@code /cs help}. mod_support #18 was
 * somebody unable to work out that second answer at all.
 */
public class StatusCommand implements ChunksmithCommand {
    private final Chunksmith chunky;

    public StatusCommand(final Chunksmith chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(final Sender sender, final CommandArguments arguments) {
        sender.sendMessagePrefixed(TranslationKey.FORMAT_STATUS_VERSION, chunky.getVersion().toString());

        final Map<String, GenerationTask> generationTasks = chunky.getGenerationTasks();
        if (generationTasks.isEmpty()) {
            sender.sendMessage(TranslationKey.FORMAT_STATUS_NO_TASKS);
        } else {
            for (final World world : chunky.getServer().getWorlds()) {
                final GenerationTask task = generationTasks.get(world.getName());
                if (task != null) {
                    task.getProgress().sendUpdate(sender);
                }
            }
        }

        // Empty on Bukkit and before a server exists. Say which of those it is rather than printing
        // nothing, because "no line at all" is indistinguishable from "the command is broken" -- and
        // that ambiguity is what this command exists to remove.
        sender.sendMessage(TranslationKey.FORMAT_STATUS_LOD,
                CsLodControl.describe().orElse("not available on this platform"));
    }

    @Override
    public List<String> suggestions(final CommandArguments arguments) {
        return List.of();
    }
}
