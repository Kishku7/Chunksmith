package com.kishku7.chunksmith.command;

import com.kishku7.chunksmith.Chunksmith;
import com.kishku7.chunksmith.Selection;
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.util.Formatting;
import com.kishku7.chunksmith.util.TranslationKey;

import java.util.List;

import static com.kishku7.chunksmith.util.Translator.translate;

public class SelectionCommand implements ChunksmithCommand {
    private final Chunksmith chunky;

    public SelectionCommand(final Chunksmith chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(final Sender sender, final CommandArguments arguments) {
        final Selection current = chunky.getSelection().build();
        sender.sendMessagePrefixed(TranslationKey.FORMAT_SELECTION);
        sender.sendMessage(TranslationKey.FORMAT_SELECTION_WORLD, current.world().getName());
        sender.sendMessage(TranslationKey.FORMAT_SELECTION_SHAPE, translate("shape_" + current.shape()));
        sender.sendMessage(TranslationKey.FORMAT_SELECTION_CENTER, Formatting.number(current.centerX()), Formatting.number(current.centerZ()));
        final double radiusX = current.radiusX();
        final double radiusZ = current.radiusZ();
        if (radiusX == radiusZ) {
            sender.sendMessage(TranslationKey.FORMAT_SELECTION_RADIUS, Formatting.number(radiusX));
        } else {
            sender.sendMessage(TranslationKey.FORMAT_SELECTION_RADII, Formatting.number(radiusX), Formatting.number(radiusZ));
        }
    }

    @Override
    public List<String> suggestions(final CommandArguments arguments) {
        return List.of();
    }
}
