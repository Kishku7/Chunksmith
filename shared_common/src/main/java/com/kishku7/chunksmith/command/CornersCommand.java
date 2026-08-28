package com.kishku7.chunksmith.command;

import com.kishku7.chunksmith.Chunksmith;
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.shape.ShapeType;
import com.kishku7.chunksmith.util.Formatting;
import com.kishku7.chunksmith.util.Input;
import com.kishku7.chunksmith.util.TranslationKey;

import java.util.List;
import java.util.Optional;

public class CornersCommand implements ChunksmithCommand {
    private final Chunksmith chunky;

    public CornersCommand(Chunksmith chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(Sender sender, CommandArguments arguments) {
        Optional<Double> x1 = arguments.next().flatMap(Input::tryDoubleSuffixed);
        Optional<Double> z1 = arguments.next().flatMap(Input::tryDoubleSuffixed);
        Optional<Double> x2 = arguments.next().flatMap(Input::tryDoubleSuffixed);
        Optional<Double> z2 = arguments.next().flatMap(Input::tryDoubleSuffixed);
        if (x1.isEmpty() || z1.isEmpty() || x2.isEmpty() || z2.isEmpty()) {
            sender.sendMessage(TranslationKey.HELP_CORNERS);
            return;
        }
        if (Input.isPastWorldLimit(x1.get()) || Input.isPastWorldLimit(z1.get()) || Input.isPastWorldLimit(x2.get()) || Input.isPastWorldLimit(z2.get())) {
            sender.sendMessage(TranslationKey.HELP_CORNERS);
            return;
        }
        double centerX = (x1.get() + x2.get()) / 2d;
        double centerZ = (z1.get() + z2.get()) / 2d;
        double radiusX = Math.abs(x1.get() - x2.get()) / 2d;
        double radiusZ = Math.abs(z1.get() - z2.get()) / 2d;
        chunky.getSelection().center(centerX, centerZ).radiusX(radiusX).radiusZ(radiusZ);
        sender.sendMessagePrefixed(TranslationKey.FORMAT_CENTER, Formatting.number(centerX), Formatting.number(centerZ));
        String shape;
        if (radiusX == radiusZ) {
            sender.sendMessagePrefixed(TranslationKey.FORMAT_RADIUS, Formatting.number(radiusX));
            shape = ShapeType.SQUARE;
        } else {
            sender.sendMessagePrefixed(TranslationKey.FORMAT_RADII, Formatting.number(radiusX), Formatting.number(radiusZ));
            shape = ShapeType.RECTANGLE;
        }
        chunky.getSelection().shape(shape);
        sender.sendMessagePrefixed(TranslationKey.FORMAT_SHAPE, shape);
    }

    @Override
    public List<String> suggestions(CommandArguments arguments) {
        return List.of();
    }
}
