package com.kishku7.chunksmith.lod;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code /cslod} -- operator commands for the CSLOD store.
 *
 * <ul>
 *   <li>{@code /cslod status} -- where the store is, and how big.</li>
 *   <li>{@code /cslod inject} -- replay the store for the current dimension into voxy. This is the
 *       backfill: LODs for a world that was pregenerated BEFORE voxy was ever installed.</li>
 * </ul>
 *
 * <p>Registered as its own root command rather than folded into {@code /chunksmith}: the shared
 * command tree lives in shared_common and is wired to TranslationKey + the lang files, which a
 * Fabric-only, voxy-only feature has no business reaching into. Folding it in can come with the
 * i18n work if the feature graduates.
 */
public final class CsLodCommand {

    private CsLodCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            final LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("cslod")
                    // 26.x replaced source.hasPermission(int) with the PermissionCheck API.
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));

            root.then(Commands.literal("status").executes(context -> {
                final ServerLevel level = context.getSource().getLevel();
                final Path store = LodSupport.storeRoot(level);
                final long bytes = sizeOf(store);
                // One line: chat renders a literal \n rather than breaking the line.
                context.getSource().sendSuccess(() -> Component.literal(
                        "[chunksmith] LOD store: " + store
                                + " | exists: " + Files.isDirectory(store)
                                + " | size: " + (bytes / 1024) + " KB"
                                + " | voxy: " + (CsLodVoxyInjector.voxyAvailable() ? "available" : "not available")), false);
                return 1;
            }));

            root.then(Commands.literal("inject").executes(context -> {
                final CommandSourceStack source = context.getSource();
                final ServerLevel level = source.getLevel();
                final Path store = LodSupport.storeRoot(level);

                if (!Files.isDirectory(store)) {
                    source.sendFailure(Component.literal("[chunksmith] no LOD store for this dimension: " + store));
                    return 0;
                }
                if (!CsLodVoxyInjector.voxyAvailable()) {
                    source.sendFailure(Component.literal(
                            "[chunksmith] voxy is not available here (its engine is client-side only, "
                                    + "so this works in singleplayer, not on a dedicated server)"));
                    return 0;
                }

                source.sendSuccess(() -> Component.literal("[chunksmith] injecting LOD store into voxy..."), true);

                // Off the server thread: this walks the whole store and waits on voxy's queue.
                final Thread worker = new Thread(() -> {
                    try {
                        CsLodVoxyInjector.inject(level, store,
                                line -> source.getServer().execute(() ->
                                        source.sendSuccess(() -> Component.literal("[chunksmith] " + line), true)));
                    } catch (final Exception e) {
                        source.getServer().execute(() -> source.sendFailure(
                                Component.literal("[chunksmith] LOD injection failed: " + e)));
                    }
                }, "chunksmith-lod-inject");
                worker.setDaemon(true);
                worker.start();
                return 1;
            }));

            dispatcher.register(root);
        });
    }

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
