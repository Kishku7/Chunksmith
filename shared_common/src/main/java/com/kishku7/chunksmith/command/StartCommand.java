package com.kishku7.chunksmith.command;

import com.kishku7.chunksmith.Chunksmith;
import com.kishku7.chunksmith.GenerationTask;
import com.kishku7.chunksmith.Selection;
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.platform.World;
import com.kishku7.chunksmith.shape.ShapeType;
import com.kishku7.chunksmith.util.Formatting;
import com.kishku7.chunksmith.util.Input;
import com.kishku7.chunksmith.util.TranslationKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.kishku7.chunksmith.util.Translator.translate;

public class StartCommand implements ChunksmithCommand {
    private final Chunksmith chunky;

    public StartCommand(Chunksmith chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(Sender sender, CommandArguments arguments) {
        if (arguments.size() > 0) {
            Optional<World> world = arguments.next().flatMap(arg -> Input.tryWorld(chunky, arg));
            if (world.isPresent()) {
                chunky.getSelection().world(world.get());
            } else {
                sender.sendMessage(TranslationKey.HELP_START);
                return;
            }
        }
        if (arguments.size() > 1) {
            Optional<String> shape = arguments.next().flatMap(Input::tryShape);
            if (shape.isPresent()) {
                chunky.getSelection().shape(shape.get());
            } else {
                sender.sendMessage(TranslationKey.HELP_START);
                return;
            }
        }
        if (arguments.size() > 2) {
            Optional<Double> centerX = arguments.next().flatMap(Input::tryDoubleSuffixed).filter(c -> !Input.isPastWorldLimit(c));
            Optional<Double> centerZ = arguments.next().flatMap(Input::tryDoubleSuffixed).filter(c -> !Input.isPastWorldLimit(c));
            if (centerX.isPresent() && centerZ.isPresent()) {
                chunky.getSelection().center(centerX.get(), centerZ.get());
            } else {
                sender.sendMessage(TranslationKey.HELP_START);
                return;
            }
        }
        if (arguments.size() > 4) {
            Optional<Double> radiusX = arguments.next().flatMap(Input::tryDoubleSuffixed).filter(r -> r >= 0 && !Input.isPastWorldLimit(r));
            if (radiusX.isPresent()) {
                chunky.getSelection().radius(radiusX.get());
            } else {
                sender.sendMessage(TranslationKey.HELP_START);
                return;
            }
        }
        if (arguments.size() > 5) {
            Optional<Double> radiusZ = arguments.next().flatMap(Input::tryDoubleSuffixed).filter(r -> r >= 0 && !Input.isPastWorldLimit(r));
            if (radiusZ.isPresent()) {
                chunky.getSelection().radiusZ(radiusZ.get());
            } else {
                sender.sendMessage(TranslationKey.HELP_START);
                return;
            }
        }
        Selection current = chunky.getSelection().build();
        if (chunky.getGenerationTasks().containsKey(current.world().getName())) {
            sender.sendMessagePrefixed(TranslationKey.FORMAT_STARTED_ALREADY, current.world().getName());
            return;
        }
        if (current.radiusX() > chunky.getLimit()) {
            sender.sendMessagePrefixed(TranslationKey.FORMAT_START_LIMIT, Formatting.number(chunky.getLimit()));
            return;
        }
        Runnable startAction = () -> {
            GenerationTask generationTask = new GenerationTask(chunky, current);
            chunky.getGenerationTasks().put(current.world().getName(), generationTask);
            chunky.getScheduler().runTask(generationTask);
            sender.sendMessagePrefixed(TranslationKey.FORMAT_START, current.world().getName(), translate("shape_" + current.shape()), Formatting.number(current.centerX()), Formatting.number(current.centerZ()), Formatting.radius(current));
        };
        if (chunky.getTaskLoader().loadTask(current.world()).filter(task -> !task.isCancelled()).isPresent()) {
            chunky.setPendingAction(sender, startAction);
            sender.sendMessagePrefixed(TranslationKey.FORMAT_START_CONFIRM, "/cs continue", "/cs confirm");
        } else {
            startAction.run();
        }
    }

    @Override
    public List<String> suggestions(CommandArguments arguments) {
        if (arguments.size() == 1) {
            List<String> suggestions = new ArrayList<>();
            chunky.getServer().getWorlds().forEach(world -> suggestions.add(world.getName()));
            return suggestions;
        } else if (arguments.size() == 2) {
            return ShapeType.all();
        }
        return List.of();
    }
}
