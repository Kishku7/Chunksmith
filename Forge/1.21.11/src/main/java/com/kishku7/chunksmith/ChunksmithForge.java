package com.kishku7.chunksmith;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import com.kishku7.chunksmith.command.ChunksmithCommand;
import com.kishku7.chunksmith.command.CommandArguments;
import com.kishku7.chunksmith.command.CommandLiteral;
import com.kishku7.chunksmith.command.suggestion.SuggestionProviders;
import com.kishku7.chunksmith.util.StructureFaultReporter;
import com.kishku7.chunksmith.util.TranslationKey;
import com.kishku7.chunksmith.event.task.GenerationTaskFinishEvent;
import com.kishku7.chunksmith.event.task.GenerationTaskUpdateEvent;
import com.kishku7.chunksmith.listeners.bossbar.BossBarTaskFinishListener;
import com.kishku7.chunksmith.listeners.bossbar.BossBarTaskUpdateListener;
import com.kishku7.chunksmith.platform.NeoForgePlayer;
import com.kishku7.chunksmith.platform.NeoForgeSender;
import com.kishku7.chunksmith.platform.NeoForgeServer;
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.platform.impl.GsonConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.string;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.arguments.DimensionArgument.dimension;
import static net.minecraft.commands.arguments.EntityArgument.player;

@Mod(ChunksmithForge.MOD_ID)
public final class ChunksmithForge {
    public static final String MOD_ID = "chunksmith";
    static { PlatformCompat.ENABLE_MOONRISE_WORKAROUNDS = ModList.get().isLoaded("moonrise"); }
    private Chunksmith chunky;
    private final Map<Identifier, ServerBossEvent> bossBars = new ConcurrentHashMap<>();

    // Forge 1.21.8+ (58.x-61.x) uses the new EventBus 7.x API. The mod constructor takes the
    // FMLJavaModLoadingContext; mod-bus events would attach to the mod bus, but Chunksmith has NO
    // registries/DeferredRegisters so it needs none. All three events it cares about are game-bus
    // lifecycle events, registered via each event's static BUS field. The addListener(this::...)
    // method references publish `this` before construction finishes, hence the benign this-escape.
    @SuppressWarnings("this-escape")
    public ChunksmithForge(final FMLJavaModLoadingContext context) {
        if (ModList.get().isLoaded("chunky")) {
            org.slf4j.LoggerFactory.getLogger("Chunksmith").error("The original Chunky mod is installed alongside Chunksmith. They share internal classes and will conflict - remove the Chunky jar and keep only Chunksmith.");
        }
        ServerStartingEvent.BUS.addListener(this::onServerStarting);
        RegisterCommandsEvent.BUS.addListener(this::onRegisterCommands);
        ServerStoppingEvent.BUS.addListener(this::onServerStopping);
    }

    public void onServerStarting(final ServerStartingEvent event) {
        final MinecraftServer server = event.getServer();
        final Path configDir = FMLPaths.CONFIGDIR.get();
        Path baseDir = configDir.resolve("chunksmith");
        final Path legacyDir = configDir.resolve("chunky");
        // Auto-migrate the legacy Chunky config on first run: if our directory does not yet
        // exist but a chunky directory does, take it over in place. If chunksmith already
        // exists, the legacy directory is left untouched. (Mirrors ChunksmithFabric.)
        if (!Files.exists(baseDir) && Files.exists(legacyDir)) {
            try {
                Files.move(legacyDir, baseDir);
                org.slf4j.LoggerFactory.getLogger("Chunksmith").info("Migrated existing config/chunky to config/chunksmith.");
            } catch (final IOException e) {
                org.slf4j.LoggerFactory.getLogger("Chunksmith").warn("Could not migrate config/chunky to config/chunksmith; using the existing chunky directory.", e);
                baseDir = legacyDir;
            }
        }
        final Path configPath = baseDir.resolve("config.json");
            StructureFaultReporter.get().setReportFile(baseDir.resolve("worldgen-faults.txt"));
        this.chunky = new Chunksmith(new NeoForgeServer(this, server), new GsonConfig(configPath));
        if (chunky.getConfig().getContinueOnRestart()) {
            chunky.getCommands().get(CommandLiteral.CONTINUE).execute(chunky.getServer().getConsole(), CommandArguments.empty());
        }
        chunky.getEventBus().subscribe(GenerationTaskUpdateEvent.class, new BossBarTaskUpdateListener(bossBars));
        chunky.getEventBus().subscribe(GenerationTaskFinishEvent.class, new BossBarTaskFinishListener(bossBars));
    }

    public void onRegisterCommands(final RegisterCommandsEvent event) {
        // Primary commands plus deprecated aliases (which emit a notice pointing to /cs).
        event.getDispatcher().register(buildCommand(CommandLiteral.CS));
        event.getDispatcher().register(buildCommand(CommandLiteral.CHUNKSMITH));
        event.getDispatcher().register(buildCommand(CommandLiteral.CHUNKY));
        event.getDispatcher().register(buildCommand(CommandLiteral.CY));
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildCommand(final String root) {
        final LiteralArgumentBuilder<CommandSourceStack> command = literal(root)
                .requires(serverCommandSource -> {
                    final MinecraftServer server = serverCommandSource.getServer();
                    //noinspection ConstantValue
                    if (server != null && server.isSingleplayer()) {
                        return true;
                    }
                    return serverCommandSource.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
                })
                .executes(context -> {
                    final Sender sender;
                    if (context.getSource().getEntity() instanceof final ServerPlayer player) {
                        sender = new NeoForgePlayer(player);
                    } else {
                        sender = new NeoForgeSender(context.getSource());
                    }
                    final Map<String, ChunksmithCommand> commands = chunky.getCommands();
                    final String input = context.getInput().substring(context.getLastChild().getNodes().get(0).getRange().getStart());
                    final String[] tokens = input.split(" ");
                    if (CommandLiteral.CHUNKY.equals(tokens[0]) || CommandLiteral.CY.equals(tokens[0])) {
                        sender.sendMessagePrefixed(TranslationKey.COMMAND_DEPRECATED_ALIAS);
                    }
                    final String subCommand = tokens.length > 1 && commands.containsKey(tokens[1]) ? tokens[1] : CommandLiteral.HELP;
                    final CommandArguments arguments = tokens.length > 2 ? CommandArguments.of(Arrays.copyOfRange(tokens, 2, tokens.length)) : CommandArguments.empty();
                    commands.get(subCommand).execute(sender, arguments);
                    return Command.SINGLE_SUCCESS;
                });
        registerArguments(command, literal(CommandLiteral.CANCEL),
                argument(CommandLiteral.WORLD, dimension()));
        registerArguments(command, literal(CommandLiteral.CENTER),
                argument(CommandLiteral.X, word()),
                argument(CommandLiteral.Z, word()));
        registerArguments(command, literal(CommandLiteral.DEBUG));
        registerArguments(command, literal(CommandLiteral.CONFIRM));
        registerArguments(command, literal(CommandLiteral.CONTINUE),
                argument(CommandLiteral.WORLD, dimension()));
        registerArguments(command, literal(CommandLiteral.CORNERS),
                argument(CommandLiteral.X1, word()),
                argument(CommandLiteral.Z1, word()),
                argument(CommandLiteral.X2, word()),
                argument(CommandLiteral.Z2, word()));
        registerArguments(command, literal(CommandLiteral.HELP),
                argument(CommandLiteral.PAGE, integer()));
        registerArguments(command, literal(CommandLiteral.PATTERN),
                argument(CommandLiteral.PATTERN, string()).suggests(SuggestionProviders.PATTERNS),
                argument(CommandLiteral.VALUE, string()));
        registerArguments(command, literal(CommandLiteral.PAUSE),
                argument(CommandLiteral.WORLD, dimension()));
        registerArguments(command, literal(CommandLiteral.PROGRESS));
        registerArguments(command, literal(CommandLiteral.QUIET),
                argument(CommandLiteral.INTERVAL, integer()));
        registerArguments(command, literal(CommandLiteral.RADIUS),
                argument(CommandLiteral.RADIUS, word()),
                argument(CommandLiteral.RADIUS, word()));
        registerArguments(command, literal(CommandLiteral.RELOAD),
                argument(CommandLiteral.TYPE, word()));
        registerArguments(command, literal(CommandLiteral.SELECTION));
        registerArguments(command, literal(CommandLiteral.SHAPE),
                argument(CommandLiteral.SHAPE, string()).suggests(SuggestionProviders.SHAPES));
        registerArguments(command, literal(CommandLiteral.SILENT));
        registerArguments(command, literal(CommandLiteral.SPAWN));
        registerArguments(command, literal(CommandLiteral.START),
                argument(CommandLiteral.WORLD, dimension()),
                argument(CommandLiteral.SHAPE, string()).suggests(SuggestionProviders.SHAPES),
                argument(CommandLiteral.CENTER_X, word()),
                argument(CommandLiteral.CENTER_Z, word()),
                argument(CommandLiteral.RADIUS_X, word()),
                argument(CommandLiteral.RADIUS_Z, word()));
        registerArguments(command, literal(CommandLiteral.TRIM),
                argument(CommandLiteral.WORLD, dimension()),
                argument(CommandLiteral.SHAPE, string()).suggests(SuggestionProviders.SHAPES),
                argument(CommandLiteral.CENTER_X, word()),
                argument(CommandLiteral.CENTER_Z, word()),
                argument(CommandLiteral.RADIUS_X, word()),
                argument(CommandLiteral.RADIUS_Z, word()),
                argument(CommandLiteral.TRIM_MODE, string()).suggests(SuggestionProviders.TRIM_MODES),
                argument(CommandLiteral.INHABITED, word()));
        registerArguments(command, literal(CommandLiteral.WORLDBORDER));
        registerArguments(command, literal(CommandLiteral.WORLD),
                argument(CommandLiteral.WORLD, dimension()));
        final LiteralArgumentBuilder<CommandSourceStack> borderCommand = literal(CommandLiteral.BORDER)
                .requires(serverCommandSource -> chunky != null && chunky.getCommands().containsKey(CommandLiteral.BORDER))
                .executes(command.getCommand());
        registerArguments(borderCommand, literal(CommandLiteral.ADD),
                argument(CommandLiteral.WORLD, dimension()),
                argument(CommandLiteral.SHAPE, string()).suggests(SuggestionProviders.SHAPES),
                argument(CommandLiteral.CENTER_X, word()),
                argument(CommandLiteral.CENTER_Z, word()),
                argument(CommandLiteral.RADIUS_X, word()),
                argument(CommandLiteral.RADIUS_Z, word()));
        registerArguments(borderCommand, literal(CommandLiteral.BYPASS),
                argument(CommandLiteral.PLAYER, player()));
        registerArguments(borderCommand, literal(CommandLiteral.HELP));
        registerArguments(borderCommand, literal(CommandLiteral.LIST));
        registerArguments(borderCommand, literal(CommandLiteral.LOAD),
                argument(CommandLiteral.WORLD, dimension()));
        registerArguments(borderCommand, literal(CommandLiteral.REMOVE),
                argument(CommandLiteral.WORLD, dimension()));
        registerArguments(borderCommand, literal(CommandLiteral.WRAP),
                argument(CommandLiteral.WRAP, word()));
        registerArguments(command, borderCommand);
        return command;
    }

    @SafeVarargs
    private <S> void registerArguments(final LiteralArgumentBuilder<S> command, final ArgumentBuilder<S, ?>... arguments) {
        for (int i = arguments.length - 1; i > 0; --i) {
            arguments[i - 1].then(arguments[i].executes(command.getCommand()));
        }
        command.then(arguments[0].executes(command.getCommand()));
    }

    public void onServerStopping(final ServerStoppingEvent event) {
        if (chunky != null) {
            chunky.disable();
        }
    }

    public Chunksmith getChunky() {
        return chunky;
    }
}
