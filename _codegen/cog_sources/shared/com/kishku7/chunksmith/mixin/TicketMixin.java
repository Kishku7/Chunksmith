package com.kishku7.chunksmith.mixin;

import net.minecraft.server.level.Ticket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads a ticket's remaining lifetime, because vanilla's own {@code isTimedOut()} cannot see the
 * state these tickets are actually stuck in.
 *
 * <pre>
 *   public boolean isTimedOut() {
 *      return this.type.hasTimeout() &amp;&amp; this.ticksLeft &lt; 0L;   // STRICTLY negative
 *   }
 * </pre>
 *
 * <p>The leaked {@code minecraft:unknown} tickets sit at exactly ZERO -- vanilla's own debug string
 * reads {@code with 0 ticks left ( out of1)}. They were decremented once, early, while their chunk
 * happened to be ready for saving, and then froze: {@code decreaseTicksLeft()} is only reached when
 * {@code canTicketExpire} passes, and that requires {@code holder.isReadyForSaving()}, which never
 * becomes true again during a heavy pre-gen. So they are one decrement short of expiry, for ever,
 * and {@code isTimedOut()} answers false about a ticket that is plainly exhausted.
 *
 * <p>That is why the first version of the purge found nothing: the predicate asked vanilla whether
 * the ticket had timed out, and vanilla's answer was technically correct and useless. Reading
 * {@code ticksLeft} directly asks the question we actually mean -- has this transient ticket used up
 * its life -- and a value of zero or less says yes.
 *
 * <p>{@code ticksLeft} is private on {@code Ticket} and present under that name on every supported
 * version, so this needs no Cog drift handling.
 */
@SuppressWarnings("UnnecessaryInterfaceModifier")
@Mixin(Ticket.class)
public interface TicketMixin {

    @Accessor("ticksLeft")
    long getTicksLeft();
}
