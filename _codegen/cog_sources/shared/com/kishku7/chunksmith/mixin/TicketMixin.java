package com.kishku7.chunksmith.mixin;

import net.minecraft.server.level.Ticket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads a ticket's remaining lifetime, because vanilla's {@code isTimedOut()} cannot see the state
 * these tickets are stuck in: it requires {@code ticksLeft} to be strictly negative.
 *
 * <p>The leaked {@code minecraft:unknown} tickets sit at exactly ZERO. Vanilla's own debug string
 * reads {@code with 0 ticks left ( out of1)}. They were decremented once, early, while their chunk
 * was briefly ready for saving, then froze: {@code decreaseTicksLeft()} needs
 * {@code canTicketExpire}, which needs {@code holder.isReadyForSaving()}, never true again during a
 * heavy pre-gen. One decrement short of expiry, for ever -- which is why the first purge, asking
 * {@code isTimedOut()}, found nothing. Zero or less on {@code ticksLeft} is the question we mean.
 */
@SuppressWarnings("UnnecessaryInterfaceModifier")
@Mixin(Ticket.class)
public interface TicketMixin {

    @Accessor("ticksLeft")
    long getTicksLeft();
}
