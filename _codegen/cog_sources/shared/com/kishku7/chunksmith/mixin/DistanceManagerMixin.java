package com.kishku7.chunksmith.mixin;

import net.minecraft.server.level.DistanceManager;
import net.minecraft.world.level.TicketStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches the ticket store so Chunksmith can NAME whatever is holding a chunk open.
 *
 * <p>Everything else was measured and ruled out: our own tickets (ledger: 15 outstanding against
 * 13,663 resident chunks), the settle window (retention continued with it disabled), a starved
 * unload pass ({@code processUnloads} consults no budget for {@code toDrop} and drains to 2000
 * regardless), and starved level propagation ({@code runAllUpdates} uses {@code Integer.MAX_VALUE}).
 * What remains is chunks at a full ticking level we did not put there, which
 * {@code TicketStorage.getTicketDebugString} names directly.
 *
 * <p>{@code ticketStorage} is package-private under that name on every supported version.
 */
@SuppressWarnings("UnnecessaryInterfaceModifier")
@Mixin(DistanceManager.class)
public interface DistanceManagerMixin {

    @Accessor("ticketStorage")
    TicketStorage getTicketStorage();
}
