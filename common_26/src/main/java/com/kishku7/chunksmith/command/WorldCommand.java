package com.kishku7.chunksmith.command;

import com.kishku7.chunksmith.Chunksmith;
import com.kishku7.chunksmith.platform.Player;
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.platform.World;
import com.kishku7.chunksmith.util.Input;
import com.kishku7.chunksmith.util.TranslationKey;

import java.util.ArrayList;
import java.util.List;

public class WorldCommand implements ChunksmithCommand {
    private final Chunksmith chunky;

    public WorldCommand(final Chunksmith chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(final Sender sender, final CommandArguments arguments) {
        final World world;
        if (arguments.size() == 0 && sender instanceof final Player player) {
            world = player.getWorld();
        } else {
            world = Input.tryWorld(chunky, arguments.joined()).orElse(null);
        }
        if (world == null) {
            sender.sendMessage(TranslationKey.HELP_WORLD);
            return;
        }
        chunky.getSelection().world(world);
        sender.sendMessagePrefixed(TranslationKey.FORMAT_WORLD, world.getName());
    }

    @Override
    public List<String> suggestions(final CommandArguments arguments) {
        if (arguments.size() == 1) {
            final List<String> suggestions = new ArrayList<>();
            chunky.getServer().getWorlds().forEach(world -> suggestions.add(world.getName()));
            return suggestions;
        }
        return List.of();
    }
}
