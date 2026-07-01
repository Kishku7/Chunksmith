package com.kishku7.chunksmith.listeners.bossbar;

import net.minecraft.network.chat.Component;
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
// # 26-only permissions API import.
// if compat.needs_permissions_import(mcver):
//     cog.outl("import net.minecraft.server.permissions.Permissions;")
//]]]
//[[[end]]]
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.Level;
import com.kishku7.chunksmith.Chunksmith;
import com.kishku7.chunksmith.GenerationTask;
import com.kishku7.chunksmith.event.task.GenerationTaskUpdateEvent;
import com.kishku7.chunksmith.platform.ServerLevelHolder;
import com.kishku7.chunksmith.platform.World;

import java.util.Map;
//[[[cog
// import cog, compat
// # java.util.UUID is only used by the 26 ServerBossEvent(UUID, ...) constructor.
// if compat.needs_uuid_import(mcver):
//     cog.outl("import java.util.UUID;")
//]]]
//[[[end]]]
import java.util.function.Consumer;

/**
 * COG DRIFT: resource-id type (ResourceLocation -> Identifier at 26), Level dimension-id accessor
 * (location() -> identifier() at 26), the player permission gate (classic hasPermissions(2) pre-26
 * vs the 26 permissions() API), and the ServerBossEvent constructor's added UUID arg. All emitted by
 * Cog so one source compiles + runs on every MC version.
 */
public class BossBarTaskUpdateListener implements Consumer<GenerationTaskUpdateEvent> {
    //[[[cog
    // import cog, compat
    // t = compat.identifier_type(mcver)
    // cog.outl("private final Map<%s, ServerBossEvent> bossBars;" % t)
    //]]]
    //[[[end]]]

    //[[[cog
    // import cog, compat
    // t = compat.identifier_type(mcver)
    // cog.outl("public BossBarTaskUpdateListener(final Map<%s, ServerBossEvent> bossBars) {" % t)
    //]]]
    //[[[end]]]
        this.bossBars = bossBars;
    }

    @Override
    public void accept(final GenerationTaskUpdateEvent event) {
        final GenerationTask task = event.generationTask();
        final Chunksmith chunky = task.getChunky();
        final World world = task.getSelection().world();
        //[[[cog
        // import cog, compat
        // t = compat.identifier_type(mcver)
        // cog.outl("final %s worldIdentifier = %s.tryParse(world.getKey());" % (t, t))
        //]]]
        //[[[end]]]
        if (worldIdentifier == null || !(world instanceof final ServerLevelHolder serverWorld)) {
            return;
        }
        final ServerBossEvent bossBar = bossBars.computeIfAbsent(worldIdentifier, x -> createNewBossBar(worldIdentifier));
        final boolean silent = chunky.getConfig().isSilent();
        if (silent == bossBar.isVisible()) {
            bossBar.setVisible(!silent);
        }
        final MinecraftServer server = serverWorld.getWorld().getServer();
        for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
            //[[[cog
            // import cog, compat
            // cog.outl("if (%s) {" % compat.gamemaster_permission_check(mcver, "player"))
            //]]]
            //[[[end]]]
                bossBar.addPlayer(player);
            } else {
                bossBar.removePlayer(player);
            }
        }
        final GenerationTask.Progress progress = task.getProgress();
        bossBar.setName(Component.nullToEmpty(String.format("%s | %s%% | %s:%s:%s",
                worldIdentifier,
                String.format("%.2f", progress.getPercentComplete()),
                String.format("%01d", progress.getHours()),
                String.format("%02d", progress.getMinutes()),
                String.format("%02d", progress.getSeconds()))));
        bossBar.setProgress(progress.getPercentComplete() / 100f);
        if (progress.isComplete()) {
            bossBar.removeAllPlayers();
            bossBars.remove(worldIdentifier);
        }
    }

    //[[[cog
    // import cog, compat
    // t = compat.identifier_type(mcver)
    // cog.outl("private ServerBossEvent createNewBossBar(final %s worldIdentifier) {" % t)
    //]]]
    //[[[end]]]
        //[[[cog
        // import cog, compat
        // if compat.server_boss_event_has_uuid_arg(mcver):
        //     cog.outl("        final ServerBossEvent bossBar = new ServerBossEvent(")
        //     cog.outl("                UUID.randomUUID(),")
        //     cog.outl("                Component.nullToEmpty(worldIdentifier.toString()),")
        //     cog.outl("                bossBarColor(worldIdentifier),")
        //     cog.outl("                BossEvent.BossBarOverlay.PROGRESS")
        //     cog.outl("        );")
        // else:
        //     cog.outl("        final ServerBossEvent bossBar = new ServerBossEvent(")
        //     cog.outl("                Component.nullToEmpty(worldIdentifier.toString()),")
        //     cog.outl("                bossBarColor(worldIdentifier),")
        //     cog.outl("                BossEvent.BossBarOverlay.PROGRESS")
        //     cog.outl("        );")
        //]]]
        //[[[end]]]
        bossBar.setDarkenScreen(false);
        bossBar.setPlayBossMusic(false);
        bossBar.setCreateWorldFog(false);
        return bossBar;
    }

    //[[[cog
    // import cog, compat
    // t = compat.identifier_type(mcver)
    // cog.outl("private static BossEvent.BossBarColor bossBarColor(%s worldIdentifier) {" % t)
    //]]]
    //[[[end]]]
        final BossEvent.BossBarColor bossBarColor;
        //[[[cog
        // import cog, compat
        // idc = compat.dimension_identifier_call(mcver)
        // cog.outl("if (Level.OVERWORLD.%s().equals(worldIdentifier)) {" % idc)
        // cog.outl("            bossBarColor = BossEvent.BossBarColor.GREEN;")
        // cog.outl("        } else if (Level.NETHER.%s().equals(worldIdentifier)) {" % idc)
        // cog.outl("            bossBarColor = BossEvent.BossBarColor.RED;")
        // cog.outl("        } else if (Level.END.%s().equals(worldIdentifier)) {" % idc)
        // cog.outl("            bossBarColor = BossEvent.BossBarColor.PURPLE;")
        //]]]
        //[[[end]]]
        } else {
            bossBarColor = BossEvent.BossBarColor.BLUE;
        }
        return bossBarColor;
    }
}
