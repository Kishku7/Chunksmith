package com.kishku7.chunksmith.lod;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import com.kishku7.chunksmith.lod.client.CsLodClientSettings;
import com.kishku7.chunksmith.lod.net.CsLodProtocol;
import com.kishku7.chunksmith.lod.net.CsLodServerNet;
//[[[cog
// import cog, compat
// # The permissions() API + Permissions class exist from 1.21.11, but 26 gates through the
// # Commands.hasPermission(Commands.LEVEL_GAMEMASTERS) predicate instead, so 26 needs NO import.
// if compat.era(mcver) == "modern_11plus" and compat._parse(mcver)[0] < 26:
//     cog.outl("import net.minecraft.server.permissions.Permissions;")
//]]]
//[[[end]]]

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.commands.arguments.EntityArgument;
import java.io.IOException;

/**
 * {@code /cslod} -- operator commands for the CSLOD store.
 *
 * <ul>
 *   <li>{@code set} -- the player's own client settings; the only subcommand a non-operator may run.</li>
 *   <li>{@code status} -- where the store is, how big, and whether the backchannel is up.</li>
 *   <li>{@code token <player>} -- mint a backchannel token by hand.</li>
 *   <li>{@code dhpush} -- replay the store into Distant Horizons. Present on every LOD cell: DH ships a
 *       build for all of them.</li>
 *   <li>{@code inject} -- replay the store into voxy. Only where a voxy jar exists to compile against
 *       (Fabric 1.21.11 + Fabric 26).</li>
 * </ul>
 *
 * <p>Both backfills are singleplayer-only: the renderer engines are client-side, so on a dedicated server
 * they report "not available" and the store is served over the backchannel to Chunksmith-Client instead.
 *
 * <p>Loader-blind: this class only builds the brigadier node; each loader's {@code LodInit} registers it
 * (Fabric via CommandRegistrationCallback, NeoForge/Forge via RegisterCommandsEvent).
 *
 * <p>Its own root command rather than folded into {@code /chunksmith}: the shared command tree lives in
 * shared_common and is wired to TranslationKey + the lang files, which the LOD feature has no business
 * reaching into.
 */
public final class CsLodCommand {

    private CsLodCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        //[[[cog
        // import cog, compat
        // cog.outl("final java.util.function.Predicate<CommandSourceStack> operatorOnly =")
        // cog.outl("        %s;" % compat.command_permission_gate(mcver, "source"))
        //]]]
        //[[[end]]]

        // The root is deliberately ungated (3.3.0): /cslod set changes the player's own client settings,
        // so a gamemaster gate on the root would stop an ordinary player changing their own config. Every
        // operator subcommand carries the gate itself instead, and brigadier hides a node whose requires()
        // fails, so a normal player sees /cslod set and nothing else.
        final LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("cslod");

        root.then(Commands.literal("status").requires(operatorOnly).executes(context -> {
            final ServerLevel level = context.getSource().getLevel();
            final Path store = LodSupport.storeRoot(level);
            final long bytes = sizeOf(store);
            // The record count, not just the byte size: the number an operator compares against their
            // chunk count to answer "does my store actually cover my world?". Header reads only (8 KB per
            // region, no record decode), so it is safe to run from a command.
            long records;
            try {
                records = CsLodPresenceIndex.countRecords(store);
            } catch (IOException e) {
                records = -1L;
            }
            final long recordCount = records;
            // One line: chat renders a literal \n rather than breaking the line.
            context.getSource().sendSuccess(() -> Component.literal(
                    "[chunksmith] " + LodSupport.describeDecision(level.getServer())
                            + " | store: " + store
                            + " | exists: " + Files.isDirectory(store)
                            + " | records: " + (recordCount < 0 ? "unreadable" : Long.toString(recordCount))
                            + " | size: " + (bytes / 1024) + " KB"
                            + renderers()
                            + " | " + CsLodServerNet.describe()), false);
            return 1;
        }));

        //[[[cog
        // import cog, compat
        // if compat.has_voxy(mcver, loader):
        //     cog.outl('root.then(Commands.literal("inject").requires(operatorOnly).executes(context -> {')
        //     cog.outl('    final CommandSourceStack source = context.getSource();')
        //     cog.outl('    final ServerLevel level = source.getLevel();')
        //     cog.outl('    final Path store = LodSupport.storeRoot(level);')
        //     cog.outl('    if (!Files.isDirectory(store)) {')
        //     cog.outl('        source.sendFailure(Component.literal("[chunksmith] no LOD store for this dimension: " + store));')
        //     cog.outl('        return 0;')
        //     cog.outl('    }')
        //     cog.outl('    if (!CsLodVoxyInjector.voxyAvailable()) {')
        //     cog.outl('        source.sendFailure(Component.literal(')
        //     cog.outl('                "[chunksmith] voxy is not available here (its engine is client-side only, "')
        //     cog.outl('                        + "so this works in singleplayer, not on a dedicated server)"));')
        //     cog.outl('        return 0;')
        //     cog.outl('    }')
        //     cog.outl('    source.sendSuccess(() -> Component.literal("[chunksmith] injecting LOD store into voxy..."), true);')
        //     cog.outl('    // Off the server thread: this walks the whole store and waits on voxy\'s queue.')
        //     cog.outl('    final Thread worker = new Thread(() -> {')
        //     cog.outl('        try {')
        //     cog.outl('            CsLodVoxyInjector.inject(level, store,')
        //     cog.outl('                    line -> source.getServer().execute(() ->')
        //     cog.outl('                            source.sendSuccess(() -> Component.literal("[chunksmith] " + line), true)));')
        //     cog.outl('        } catch (final Exception e) {')
        //     cog.outl('            source.getServer().execute(() -> source.sendFailure(')
        //     cog.outl('                    Component.literal("[chunksmith] LOD injection failed: " + e)));')
        //     cog.outl('        }')
        //     cog.outl('    }, "chunksmith-lod-inject");')
        //     cog.outl('    worker.setDaemon(true);')
        //     cog.outl('    worker.start();')
        //     cog.outl('    return 1;')
        //     cog.outl('}));')
        // else:
        //     cog.outl("// /cslod inject is absent on this cell: it compiles directly against the voxy jar, and voxy")
        //     cog.outl("// (Fabric-only; never published for 1.20.1 or 1.21.1) has no build for this (loader, MC).")
        //     cog.outl("// Distant Horizons IS served here -- see /cslod dhpush below.")
        //
        // if compat.has_dh(mcver, loader):
        //     cog.outl('')
        //     cog.outl('root.then(Commands.literal("dhpush").requires(operatorOnly).executes(context -> {')
        //     cog.outl('    final CommandSourceStack source = context.getSource();')
        //     cog.outl('    final ServerLevel level = source.getLevel();')
        //     cog.outl('    final Path store = LodSupport.storeRoot(level);')
        //     cog.outl('    if (!Files.isDirectory(store)) {')
        //     cog.outl('        source.sendFailure(Component.literal("[chunksmith] no LOD store for this dimension"));')
        //     cog.outl('        return 0;')
        //     cog.outl('    }')
        //     cog.outl('    if (!LodPlatform.isModLoaded("distanthorizons")) {')
        //     cog.outl('        source.sendFailure(Component.literal("[chunksmith] Distant Horizons is not installed"));')
        //     cog.outl('        return 0;')
        //     cog.outl('    }')
        //     cog.outl('    // THIS level\'s wrapper -- never "the last one DH mentioned". DH loads every dimension')
        //     cog.outl('    // at startup, so a last-wins wrapper is the END, and DH will happily (and silently)')
        //     cog.outl('    // accept overworld chunks into the end\'s database.')
        //     cog.outl('    final var wrapper = CsLodDhSupport.wrapperFor(level);')
        //     cog.outl('    if (wrapper == null) {')
        //     cog.outl('        source.sendFailure(Component.literal(')
        //     cog.outl('                "[chunksmith] DH has not reported this level yet -- rejoin the world and retry"));')
        //     cog.outl('        return 0;')
        //     cog.outl('    }')
        //     cog.outl('    source.sendSuccess(() -> Component.literal(')
        //     cog.outl('            "[chunksmith] pushing LOD store into Distant Horizons -> " + wrapper.getDhIdentifier()), true);')
        //     cog.outl('    final Thread worker = new Thread(() -> {')
        //     cog.outl('        try {')
        //     cog.outl('            CsLodDhPusher.push(level, wrapper, store,')
        //     cog.outl('                    line -> source.getServer().execute(() ->')
        //     cog.outl('                            source.sendSuccess(() -> Component.literal("[chunksmith] " + line), true)));')
        //     cog.outl('        } catch (final Exception e) {')
        //     cog.outl('            source.getServer().execute(() -> source.sendFailure(')
        //     cog.outl('                    Component.literal("[chunksmith] DH push failed: " + e)));')
        //     cog.outl('        }')
        //     cog.outl('    }, "chunksmith-dh-push");')
        //     cog.outl('    worker.setDaemon(true);')
        //     cog.outl('    worker.start();')
        //     cog.outl('    return 1;')
        //     cog.outl('}));')
        // else:
        //     cog.outl("// /cslod dhpush is absent on this cell: no LOD renderer exists for this (loader, MC) at all.")
        //]]]
        //[[[end]]]

        root.then(Commands.literal("token").requires(operatorOnly)
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> {
                            final ServerPlayer target =
                                    EntityArgument.getPlayer(context, "player");
                            final String token = CsLodServerNet.issueFor(target);
                            if (token == null) {
                                context.getSource().sendFailure(Component.literal(
                                        "[chunksmith] the LOD backchannel is not running"));
                                return 0;
                            }
                            //[[[cog
                            // import cog, compat
                            // cog.outl('context.getSource().sendSuccess(() -> Component.literal(')
                            // cog.outl('        "[chunksmith] token for " + target.getGameProfile().%s() + ": " + token), false);'
                            //          % compat.profile_name_call(mcver))
                            //]]]
                            //[[[end]]]
                            return 1;
                        })));

        // Referencing CsLodClientSettings from server-side code is not a side-guard breach: it and
        // CsLodClientConfig name no net.minecraft.client type at all -- they are java.util.Properties and
        // two static fields. What must never be reached from here is the renderer/download half, and none
        // of it is. No permission gate: config/chunksmith-lod.properties is the player's own file, on
        // their own machine; the command forwards and the client answers.
        root.then(Commands.literal("set")
                .executes(context -> clientSetting(context.getSource(),
                        CsLodProtocol.SETTING_LIST, "", ""))
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            for (String name : CsLodClientSettings.names()) {
                                builder.suggest(name);
                            }
                            return builder.buildFuture();
                        })
                        .executes(context -> clientSetting(context.getSource(),
                                CsLodProtocol.SETTING_SHOW,
                                StringArgumentType.getString(context, "name"), ""))
                        .then(Commands.argument("value", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    // Completions come from the setting, so they cannot drift from it.
                                    final var setting = CsLodClientSettings.find(
                                            StringArgumentType.getString(context, "name"));
                                    if (setting.isPresent()) {
                                        for (String option : setting.get().kind().completions()) {
                                            builder.suggest(option);
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(context -> clientSetting(context.getSource(),
                                        CsLodProtocol.SETTING_SET,
                                        StringArgumentType.getString(context, "name"),
                                        StringArgumentType.getString(context, "value"))))));

        return root;
    }

    /**
     * Forward a client-settings request to the player's own client. Deliberately silent on success: the
     * client prints the answer, because it is the side that reads and writes the file.
     *
     * <p>{@link CsLodServerNet#hasLodClient} exists for the refusal path. An unknown message id is dropped
     * silently at the far end, so without the check a player on a vanilla client would type the command,
     * see nothing, and have no way to tell success from an empty room.
     */
    private static int clientSetting(final CommandSourceStack source,
                                     final byte action,
                                     final String name,
                                     final String value) throws CommandSyntaxException {
        final ServerPlayer player = source.getPlayerOrException();
        if (!CsLodServerNet.hasLodClient(player)) {
            // The "no renderer" half of this message is gone (3.4.0): the client now introduces itself
            // whether or not it has voxy or Distant Horizons, precisely so these settings stay reachable,
            // so blaming a missing renderer would now be a wrong answer. What is left is the honest
            // remainder -- no hello means either no Chunksmith on that client, or a different LOD protocol
            // version (which the client reports in its own log, by version number, when it happens).
            source.sendFailure(Component.literal(
                    "[chunksmith] this server has not heard from your client's Chunksmith, so it cannot"
                            + " reach your client settings. Either Chunksmith is not installed"
                            + " client-side, or your version speaks a different LOD protocol than this"
                            + " server -- your client's log names which. Editing"
                            + " config/chunksmith-lod.properties by hand always works."));
            return 0;
        }
        if (!CsLodServerNet.sendClientSetting(player, action, name, value)) {
            source.sendFailure(Component.literal(
                    "[chunksmith] could not send that request to your client"));
            return 0;
        }
        return 1;
    }

    /**
     * The renderer fields of the status line. A cell reports only the renderers it can actually feed:
     * where voxy has no build the line says so, rather than "not available" for something that could
     * never be available.
     */
    private static String renderers() {
        //[[[cog
        // import cog, compat
        // parts = []
        // if compat.has_voxy(mcver, loader):
        //     parts.append('" | voxy: " + (CsLodVoxyInjector.voxyAvailable() ? "available" : "not available")')
        // elif compat.has_dh(mcver, loader):
        //     parts.append('" | voxy: no build for this loader/MC"')
        // if compat.has_dh(mcver, loader):
        //     parts.append('" | dh: " + dhStatus()')
        // if parts:
        //     cog.outl("return %s;" % ("\n        + ".join(parts)))
        // else:
        //     cog.outl('// No renderer exists for this cell -- the store is served, not injected.')
        //     cog.outl('return "";')
        //]]]
        //[[[end]]]
    }

    //[[[cog
    // import cog, compat
    // if compat.has_dh(mcver, loader):
    //     cog.outl('/** CsLodDhSupport hard-references DH types, so only touch it when DH is actually installed. */')
    //     cog.outl('private static String dhStatus() {')
    //     cog.outl('    if (!LodPlatform.isModLoaded("distanthorizons")) {')
    //     cog.outl('        return "not installed";')
    //     cog.outl('    }')
    //     cog.outl('    try {')
    //     cog.outl('        return CsLodDhSupport.describe();')
    //     cog.outl('    } catch (final LinkageError error) {')
    //     cog.outl('        return "incompatible";')
    //     cog.outl('    }')
    //     cog.outl('}')
    //]]]
    //[[[end]]]

    private static long sizeOf(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0L;
        }
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (Exception e) {
                    return 0L;
                }
            }).sum();
        } catch (Exception e) {
            return 0L;
        }
    }
}
