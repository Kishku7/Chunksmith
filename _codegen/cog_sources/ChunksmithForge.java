/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) 2025-2026 Kishku7
 *
 * Chunksmith is a fork of Chunky (https://github.com/pop4959/Chunky)
 * by pop4959 and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.kishku7.chunksmith;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
//[[[cog
// import cog, compat
// cog.outl(compat.identifier_import(mcver))
//]]]
//[[[end]]]
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
//[[[cog
// import cog, compat
// # Classic-Forge eventbus import (old) OR the 26-era permissions import (new+1.21.11); never both.
// if not compat.forge_new_eventbus(mcver):
//     cog.outl("import net.minecraftforge.eventbus.api.SubscribeEvent;")
// elif compat.needs_permissions_import(mcver):
//     cog.outl("import net.minecraft.server.permissions.Permissions;")
//]]]
//[[[end]]]
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
//[[[cog
// import cog, compat
// if compat.forge_new_eventbus(mcver):
//     cog.outl("import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;")
//]]]
//[[[end]]]
import net.minecraftforge.fml.loading.FMLPaths;
//[[[cog
// import cog, compat
// if not compat.forge_new_eventbus(mcver):
//     cog.outl("import net.minecraftforge.common.MinecraftForge;")
//]]]
//[[[end]]]
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
//[[[cog
// import cog, compat
// # WORLD-ENTER PREGEN (mod_support #20). Gated with the feature: ServerStartedEvent is referenced
// # only by the handler below, so an ungated cell would carry an unused import.
// if compat.has_world_enter(mcver, loader):
//     cog.outl("import net.minecraftforge.event.server.ServerStartedEvent;")
//]]]
//[[[end]]]
import net.minecraftforge.event.server.ServerStoppingEvent;
import com.kishku7.chunksmith.command.ChunksmithCommand;
import com.kishku7.chunksmith.command.CommandArguments;
import com.kishku7.chunksmith.command.CommandLiteral;
import com.kishku7.chunksmith.command.suggestion.SuggestionProviders;
import com.kishku7.chunksmith.util.ServerSideRendererAdvisory;
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
import org.slf4j.LoggerFactory;

@Mod(ChunksmithForge.MOD_ID)
public final class ChunksmithForge {
    public static final String MOD_ID = "chunksmith";
    static { PlatformCompat.ENABLE_MOONRISE_WORKAROUNDS = ModList.get().isLoaded("moonrise"); }
    private Chunksmith chunky;
    //[[[cog
    // import cog, compat
    // cog.outl("private final Map<%s, ServerBossEvent> bossBars = new ConcurrentHashMap<>();" % compat.identifier_type(mcver))
    //]]]
    //[[[end]]]

    //[[[cog
    // import cog, compat
    // if compat.forge_new_eventbus(mcver):
    //     cog.outl("// Forge 1.21.8+ (58.x-61.x) uses the new EventBus 7.x API. The mod constructor takes the")
    //     cog.outl("// FMLJavaModLoadingContext; mod-bus events would attach to the mod bus, but Chunksmith has NO")
    //     cog.outl("// registries/DeferredRegisters so it needs none. All three events it cares about are game-bus")
    //     cog.outl("// lifecycle events, registered via each event's static BUS field. The addListener(this::...)")
    //     cog.outl("// method references publish `this` before construction finishes, hence the benign this-escape.")
    // else:
    //     cog.outl("// MinecraftForge.EVENT_BUS.register(this) is the documented FML registration pattern; the bus stores")
    //     cog.outl("// the fully-constructed handler and does not call back during construction, so the this-escape")
    //     cog.outl("// is benign here. (26/JDK25 does not flag it; pre-26/JDK21 does.)")
    //]]]
    //[[[end]]]
    @SuppressWarnings("this-escape")
    //[[[cog
    // import cog, compat
    // if compat.forge_new_eventbus(mcver):
    //     cog.outl("public ChunksmithForge(final FMLJavaModLoadingContext context) {")
    // else:
    //     cog.outl("public ChunksmithForge() {")
    //]]]
    //[[[end]]]
        if (ModList.get().isLoaded("chunky")) {
            LoggerFactory.getLogger("Chunksmith").error("The original Chunky mod is installed alongside Chunksmith. They share internal classes and will conflict - remove the Chunky jar and keep only Chunksmith.");
        }
        //[[[cog
        // import cog, compat
        // if compat.forge_new_eventbus(mcver):
        //     cog.outl("ServerStartingEvent.BUS.addListener(this::onServerStarting);")
        //     cog.outl("RegisterCommandsEvent.BUS.addListener(this::onRegisterCommands);")
        //     cog.outl("ServerStoppingEvent.BUS.addListener(this::onServerStopping);")
        //     # WORLD-ENTER PREGEN: EventBus 7.x does no annotation scan, so a handler that is never
        //     # explicitly registered is never called. THIS LINE IS THE ARMING PATH -- without it the
        //     # server half never runs and the client screen is never reached.
        //     if compat.has_world_enter(mcver, loader):
        //         cog.outl("ServerStartedEvent.BUS.addListener(this::onServerStartedWorldEnter);")
        // else:
        //     cog.outl("MinecraftForge.EVENT_BUS.register(this);")
        //]]]
        //[[[end]]]
    }

    //[[[cog
    // import cog, compat
    // if not compat.forge_new_eventbus(mcver):
    //     cog.outl("@SubscribeEvent")
    //]]]
    //[[[end]]]
    public void onServerStarting(ServerStartingEvent event) {
        final MinecraftServer server = event.getServer();
        // An LOD renderer on a dedicated server is duplicated work Chunksmith does not need. It
        // builds its own LOD data and serves it to each player's client. Say so once, at startup, and
        // do not act on it: it is the operator's machine. See ServerSideRendererAdvisory.
        ServerSideRendererAdvisory.message(server.isDedicatedServer(), id -> ModList.get().isLoaded(id))
                .ifPresent(message -> LoggerFactory.getLogger("Chunksmith").warn(message));
        final Path configDir = FMLPaths.CONFIGDIR.get();
        Path baseDir = configDir.resolve("chunksmith");
        final Path legacyDir = configDir.resolve("chunky");
        // Auto-migrate the legacy Chunky config on first run: if our directory does not yet
        // exist but a chunky directory does, take it over in place. If chunksmith already
        // exists, the legacy directory is left untouched. (Mirrors ChunksmithFabric.)
        if (!Files.exists(baseDir) && Files.exists(legacyDir)) {
            try {
                Files.move(legacyDir, baseDir);
                LoggerFactory.getLogger("Chunksmith").info("Migrated existing config/chunky to config/chunksmith.");
            } catch (IOException e) {
                LoggerFactory.getLogger("Chunksmith").warn("Could not migrate config/chunky to config/chunksmith; using the existing chunky directory.", e);
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

    //[[[cog
    // import cog, compat
    // if not compat.forge_new_eventbus(mcver):
    //     cog.outl("@SubscribeEvent")
    //]]]
    //[[[end]]]
    public void onRegisterCommands(RegisterCommandsEvent event) {
        // Primary commands plus deprecated aliases (which emit a notice pointing to /cs).
        event.getDispatcher().register(buildCommand(CommandLiteral.CS));
        event.getDispatcher().register(buildCommand(CommandLiteral.CHUNKSMITH));
        event.getDispatcher().register(buildCommand(CommandLiteral.CHUNKY));
        event.getDispatcher().register(buildCommand(CommandLiteral.CY));
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildCommand(String root) {
        final LiteralArgumentBuilder<CommandSourceStack> command = literal(root)
                .requires(serverCommandSource -> {
                    final MinecraftServer server = serverCommandSource.getServer();
                    //noinspection ConstantValue
                    if (server != null && server.isSingleplayer()) {
                        return true;
                    }
                    //[[[cog
                    // import cog, compat
                    // # CommandSourceStack permission gate. Old = hasPermission(int) (singular); 26/1.21.11
                    // # = permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER). This is the
                    // # CommandSourceStack method (singular), NOT ServerPlayer.hasPermissions (plural), so it
                    // # cannot reuse compat.gamemaster_permission_check (that targets a player var).
                    // if compat.needs_permissions_import(mcver):
                    //     cog.outl("return serverCommandSource.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);")
                    // else:
                    //     cog.outl("return serverCommandSource.hasPermission(2);")
                    //]]]
                    //[[[end]]]
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
        registerArguments(command, literal(CommandLiteral.DEBUG),
                argument(CommandLiteral.VALUE, word()));
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
        registerArguments(command, literal(CommandLiteral.SET),
                argument(CommandLiteral.TYPE, string()).suggests(SuggestionProviders.SETTINGS),
                argument(CommandLiteral.VALUE, string()));
        registerArguments(command, literal(CommandLiteral.SELECTION));
        registerArguments(command, literal(CommandLiteral.SHAPE),
                argument(CommandLiteral.SHAPE, string()).suggests(SuggestionProviders.SHAPES));
        registerArguments(command, literal(CommandLiteral.SILENT));
        registerArguments(command, literal(CommandLiteral.SPAWN));
        registerArguments(command, literal(CommandLiteral.STATUS));
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
    private <S> void registerArguments(LiteralArgumentBuilder<S> command, ArgumentBuilder<S, ?>... arguments) {
        for (int i = arguments.length - 1; i > 0; --i) {
            arguments[i - 1].then(arguments[i].executes(command.getCommand()));
        }
        command.then(arguments[0].executes(command.getCommand()));
    }

    //[[[cog
    // import cog, compat
    // if not compat.forge_new_eventbus(mcver):
    //     cog.outl("@SubscribeEvent")
    //]]]
    //[[[end]]]
    public void onServerStopping(ServerStoppingEvent event) {
        if (chunky != null) {
            chunky.disable();
        }
    }

    // WORLD-ENTER PREGEN (mod_support #20). ServerSTARTED, not ServerSTARTING: the Chunksmith
    // instance is built in onServerStarting above, and the orchestrator calls ChunksmithProvider.get()
    // immediately. WorldEnterPregen is fully qualified so the gate can remove the whole hook without
    // leaving an unused import behind on ungated cells. Registration differs by era -- classic Forge
    // scans @SubscribeEvent, EventBus 7.x needs the explicit BUS.addListener in the constructor.
    //[[[cog
    // import cog, compat
    // if compat.has_world_enter(mcver, loader):
    //     if not compat.forge_new_eventbus(mcver):
    //         cog.outl("@SubscribeEvent")
    //     cog.outl("public void onServerStartedWorldEnter(ServerStartedEvent event) {")
    //     cog.outl("    final MinecraftServer server = event.getServer();")
    //     cog.outl("    com.kishku7.chunksmith.worldenter.WorldEnterPregen.onServerStarted(server, !server.isDedicatedServer());")
    //     cog.outl("}")
    //]]]
    //[[[end]]]

    public Chunksmith getChunky() {
        return chunky;
    }
}
