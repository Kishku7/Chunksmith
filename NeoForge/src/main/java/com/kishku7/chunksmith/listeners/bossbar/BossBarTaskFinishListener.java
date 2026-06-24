package com.kishku7.chunksmith.listeners.bossbar;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerBossEvent;
import com.kishku7.chunksmith.GenerationTask;
import com.kishku7.chunksmith.event.task.GenerationTaskFinishEvent;
import com.kishku7.chunksmith.platform.World;

import java.util.Map;
import java.util.function.Consumer;

public class BossBarTaskFinishListener implements Consumer<GenerationTaskFinishEvent> {
    private final Map<Identifier, ServerBossEvent> bossBars;

    public BossBarTaskFinishListener(final Map<Identifier, ServerBossEvent> bossBars) {
        this.bossBars = bossBars;
    }

    @Override
    public void accept(final GenerationTaskFinishEvent event) {
        final GenerationTask task = event.generationTask();
        final World world = task.getSelection().world();
        final Identifier worldIdentifier = Identifier.tryParse(world.getKey());
        if (worldIdentifier == null) {
            return;
        }
        final ServerBossEvent bossBar = bossBars.get(worldIdentifier);
        if (bossBar != null) {
            bossBar.removeAllPlayers();
            bossBars.remove(worldIdentifier);
        }
    }
}
