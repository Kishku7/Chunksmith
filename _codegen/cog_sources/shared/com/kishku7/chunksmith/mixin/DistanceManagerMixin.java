package com.kishku7.chunksmith.mixin;

import net.minecraft.server.level.DistanceManager;
import net.minecraft.world.level.TicketStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches the ticket store so Chunksmith can NAME whatever is holding a chunk open.
 *
 * <p>By this point every other explanation for unbounded chunk retention during a pre-gen has been
 * measured and ruled out: Chunksmith's own tickets (its ledger showed 15 outstanding against 13,663
 * resident chunks), the settle window (retention continued with it disabled entirely), a starved
 * unload pass (vanilla's {@code processUnloads} consults no budget for {@code toDrop} and drains its
 * queue to 2000 regardless), and starved level propagation ({@code runAllUpdates} propagates with
 * {@code Integer.MAX_VALUE}, i.e. to completion). What remains is that hundreds of chunks sit at a
 * full ticking level that Chunksmith did not put there.
 *
 * <p>{@code TicketStorage.getTicketDebugString} answers "who" directly. Every previous attempt to
 * answer it by reasoning produced a confident wrong answer, so this stops reasoning and reads the
 * ticket.
 *
 * <p>{@code ticketStorage} is package-private on the abstract {@code DistanceManager}, present under
 * that name on every supported version, so this needs no Cog handling.
 */
@SuppressWarnings("UnnecessaryInterfaceModifier")
@Mixin(DistanceManager.class)
public interface DistanceManagerMixin {

    @Accessor("ticketStorage")
    TicketStorage getTicketStorage();
}
