package com.kishku7.chunksmith.command;

import com.kishku7.chunksmith.Chunky;
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.platform.util.Location;
import com.kishku7.chunksmith.util.Formatting;
import com.kishku7.chunksmith.util.Input;
import com.kishku7.chunksmith.util.TranslationKey;

import java.util.List;
import java.util.Optional;

public class CenterCommand implements ChunkyCommand {
    private final Chunky chunky;

    public CenterCommand(final Chunky chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(final Sender sender, final CommandArguments arguments) {
        final Optional<Double> newX = arguments.next().flatMap(Input::tryDoubleSuffixed);
        final Optional<Double> newZ = arguments.next().flatMap(Input::tryDoubleSuffixed);
        final double centerX;
        final double centerZ;
        if (newX.isEmpty() && newZ.isEmpty()) {
            final Location coordinate = sender.getLocation();
            centerX = coordinate.getX();
            centerZ = coordinate.getZ();
        } else if (newX.isPresent() && newZ.isPresent()) {
            centerX = newX.get();
            centerZ = newZ.get();
        } else {
            sender.sendMessage(TranslationKey.HELP_CENTER);
            return;
        }
        if (Input.isPastWorldLimit(centerX) || Input.isPastWorldLimit(centerZ)) {
            sender.sendMessage(TranslationKey.HELP_CENTER);
            return;
        }
        chunky.getSelection().center(centerX, centerZ);
        sender.sendMessagePrefixed(TranslationKey.FORMAT_CENTER, Formatting.number(centerX), Formatting.number(centerZ));
    }

    @Override
    public List<String> suggestions(final CommandArguments arguments) {
        return List.of();
    }
}
