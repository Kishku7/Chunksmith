package com.kishku7.chunksmith.command;

import com.kishku7.chunksmith.Chunksmith;
import com.kishku7.chunksmith.GenerationTask;
import com.kishku7.chunksmith.lod.net.CsLodControl;
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.platform.World;
import com.kishku7.chunksmith.util.TranslationKey;

import java.util.List;
import java.util.Map;

public class StatusCommand implements ChunksmithCommand {
    private final Chunksmith chunky;

    public StatusCommand(Chunksmith chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(Sender sender, CommandArguments arguments) {
        sender.sendMessagePrefixed(TranslationKey.FORMAT_STATUS_VERSION, chunky.getVersion().toString());

        final Map<String, GenerationTask> generationTasks = chunky.getGenerationTasks();
        if (generationTasks.isEmpty()) {
            sender.sendMessage(TranslationKey.FORMAT_STATUS_NO_TASKS);
        } else {
            for (World world : chunky.getServer().getWorlds()) {
                final GenerationTask task = generationTasks.get(world.getName());
                if (task != null) {
                    task.getProgress().sendUpdate(sender);
                }
            }
        }

        sender.sendMessage(TranslationKey.FORMAT_STATUS_LOD,
                CsLodControl.describe().orElse("not available on this platform"));
    }

    @Override
    public List<String> suggestions(CommandArguments arguments) {
        return List.of();
    }
}
