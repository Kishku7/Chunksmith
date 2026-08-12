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

/**
 * {@code /cslod} -- operator commands for the CSLOD store.
 *
 * <ul>
 *   <li>{@code /cslod set} -- list, show or change the PLAYER'S OWN client settings. The only
 *       subcommand any player may run: the others are operator tools, this one is their config.</li>
 *   <li>{@code /cslod status} -- where the store is, how big, and whether the backchannel is up.</li>
 *   <li>{@code /cslod token <player>} -- mint a backchannel token by hand (the "why can't my client
 *       download?" answer).</li>
 *   <li>{@code /cslod dhpush} -- replay the store into Distant Horizons. Present on every LOD cell: DH
 *       ships a build for all of them.</li>
 *   <li>{@code /cslod inject} -- replay the store into voxy. Present ONLY where a voxy jar exists to
 *       compile against (Fabric 1.21.11 + Fabric 26).</li>
 * </ul>
 *
 * <p>Both are SINGLEPLAYER backfills: the renderer engines are client-side, so on a dedicated server they
 * report "not available" and the store is served over the backchannel to Chunksmith-Client instead.
 *
 * <p>Loader-blind by construction: this class only BUILDS the brigadier node. Each loader's
 * {@code LodInit} registers it (Fabric via CommandRegistrationCallback, NeoForge/Forge via
 * RegisterCommandsEvent).
 *
 * <p>Registered as its own root command rather than folded into {@code /chunksmith}: the shared
 * command tree lives in shared_common and is wired to TranslationKey + the lang files, which the LOD
 * feature has no business reaching into. Folding it in can come with the i18n work if the feature
 * graduates.
 *
 * <p>SHARED SOURCE -- canonical location: _codegen/cog_sources/lod. Edit ONLY there; the per-cell
 * copy under gen/ is overwritten by cog-gen on every build.
 */
public final class CsLodCommand {

    private CsLodCommand() {
    }

    /** Build the {@code /cslod} node. The caller registers it with its loader's dispatcher. */
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        //[[[cog
        // import cog, compat
        // cog.outl("final java.util.function.Predicate<CommandSourceStack> operatorOnly =")
        // cog.outl("        %s;" % compat.command_permission_gate(mcver, "source"))
        //]]]
        //[[[end]]]

        // The ROOT is deliberately UNGATED (3.3.0). /cslod set changes the PLAYER'S OWN client
        // settings, so a gamemaster gate on the root would have meant an ordinary player could not
        // change their own config -- which is precisely the "not really a setting" problem the house
        // rule exists to fix. Every OPERATOR subcommand carries the gate itself instead, and brigadier
        // hides a node whose requires() fails, so a normal player sees /cslod set and nothing else.
        final LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("cslod");

        root.then(Commands.literal("status").requires(operatorOnly).executes(context -> {
            final ServerLevel level = context.getSource().getLevel();
            final Path store = LodSupport.storeRoot(level);
            final long bytes = sizeOf(store);
            // The record COUNT, not just the byte size. This is the number an operator compares against
            // their chunk count to answer "does my store actually cover my world?" -- and, since a
            // re-run now backfills LOD holes, the number that should climb to match after one. Header
            // reads only (8 KB per region, no record decode), so it is safe to run from a command.
            long records;
            try {
                records = CsLodPresenceIndex.countRecords(store);
            } catch (final java.io.IOException e) {
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
                            + " | " + com.kishku7.chunksmith.lod.net.CsLodServerNet.describe()), false);
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
                .then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
                        .executes(context -> {
                            final net.minecraft.server.level.ServerPlayer target =
                                    net.minecraft.commands.arguments.EntityArgument.getPlayer(context, "player");
                            final String token = com.kishku7.chunksmith.lod.net.CsLodServerNet.issueFor(target);
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

        // /cslod set -- the PLAYER'S OWN client settings (config/chunksmith-lod.properties), which
        // live on their machine and not on this one. The command forwards; the client answers. See
        // CsLodProtocol.S2C_CLIENT_SETTING for why. NO permission gate: it is their own config.
        //
        // Referencing CsLodClientSettings from server-side code is safe and is NOT a side-guard
        // breach: it and CsLodClientConfig name no net.minecraft.client type at all -- they are
        // java.util.Properties and two static fields. What must never be reached from here is the
        // renderer/download half, and none of it is.
        root.then(Commands.literal("set")
                .executes(context -> clientSetting(context.getSource(),
                        CsLodProtocol.SETTING_LIST, "", ""))
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            for (final String name : CsLodClientSettings.names()) {
                                builder.suggest(name);
                            }
                            return builder.buildFuture();
                        })
                        .executes(context -> clientSetting(context.getSource(),
                                CsLodProtocol.SETTING_SHOW,
                                StringArgumentType.getString(context, "name"), ""))
                        .then(Commands.argument("value", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    // Completions come from the SETTING, so they can never drift from
                                    // what the setting will accept.
                                    final var setting = CsLodClientSettings.find(
                                            StringArgumentType.getString(context, "name"));
                                    if (setting.isPresent()) {
                                        for (final String option : setting.get().kind().completions()) {
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
     * Forward a client-settings request to the player's own client.
     *
     * <p>Deliberately SILENT on success. The client prints the answer because the client is the only
     * side that knows it -- it is the one that reads and writes the file. A confirmation from here as
     * well would be a second message, from the side that cannot actually see the outcome.
     *
     * <p>The refusal path is the point of {@link CsLodServerNet#hasLodClient}: an unknown message id is
     * dropped silently at the far end, so without the check a player on a vanilla client would type the
     * command, see nothing, and have no way to tell success from an empty room.
     */
    private static int clientSetting(final CommandSourceStack source,
                                     final byte action,
                                     final String name,
                                     final String value) throws CommandSyntaxException {
        final ServerPlayer player = source.getPlayerOrException();
        if (!CsLodServerNet.hasLodClient(player)) {
            source.sendFailure(Component.literal(
                    "[chunksmith] these are CLIENT settings, and your client has not spoken to this"
                            + " server's Chunksmith. Install or enable Chunksmith on your client, or"
                            + " edit config/chunksmith-lod.properties yourself."));
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
     * The renderer fields of the status line.
     *
     * <p>A cell reports ONLY the renderers it can actually feed. Where voxy has no build, the line says so
     * rather than reporting "not available" for something that could never be available -- claiming a
     * renderer you cannot feed is exactly the failure this whole gate exists to prevent.
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

    private static long sizeOf(final Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0L;
        }
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (final Exception e) {
                    return 0L;
                }
            }).sum();
        } catch (final Exception e) {
            return 0L;
        }
    }
}
