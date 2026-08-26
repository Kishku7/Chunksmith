package com.kishku7.chunksmith.ducks;

import java.util.function.BooleanSupplier;

public interface MinecraftServerExtension {
    void chunksmith$runChunkSystemHousekeeping(BooleanSupplier haveTime);

    void chunksmith$markChunkSystemHousekeeping();

    /**
     * Queue one piece of CHUNK-TICKET work to run at Chunksmith's ticket safe point.
     *
     * <p><b>Why a safe point exists at all (mod_support #16, 2026-08-12).</b> Being on the server
     * thread is not enough to make a ticket mutation safe. The server thread spends the whole of
     * {@code ServerLevel.tick} inside one fastutil iteration: {@code ServerChunkCache.tickChunks}
     * calls {@code ChunkMap.forEachBlockTickingChunk}, which calls
     * {@code DistanceManager.forEachEntityTickingChunk}, which walks
     * {@code simulationChunkTracker.chunks} -- a {@code Long2ByteOpenHashMap} -- and ticks every
     * loaded chunk from inside the loop body.
     *
     * <p>That map is written by {@code ChunkTracker.setLevel}, which runs only from
     * {@code DistanceManager.runAllUpdates}, i.e. from
     * {@code ServerChunkCache.runDistanceManagerUpdates()}. Vanilla flushes those updates at
     * {@code ServerChunkCache.tick} immediately BEFORE {@code tickChunks}, so the tracker queues are
     * empty when the walk begins and nothing modifies the map underneath it.
     *
     * <p>Unless somebody adds work during the walk. A ticket add or remove queues a level update; the
     * next pump of the chunk-source executor applies it, because
     * {@code ServerChunkCache.MainThreadExecutor.pollTask()} calls {@code runDistanceManagerUpdates()}
     * as its first act. Both of those happen inside the walk. The map is then structurally modified
     * mid-iteration and fastutil's iterator dies on its own null {@code wrapped} list:
     * "Cannot invoke LongArrayList.getLong(int) because this.wrapped is null". A pre-gen mutates
     * tickets continuously, so it supplies that work every tick; C2ME pumps often enough during a tick
     * to collect on it.
     *
     * <p>So ticket work does not go on the server executor. It comes here, and runs once per tick from
     * the {@code tickServer} housekeeping hook -- outside every chunk-system iteration, and immediately
     * before the housekeeping pass flushes the distance manager itself.
     *
     * <p>Safe to call from any thread.
     */
    void chunksmith$atTicketSafePoint(Runnable task);

    /**
     * Run the queued ticket work NOW, on the calling (server) thread.
     *
     * <p>Exists for ONE caller: the paused integrated server. The ordinary drain rides
     * {@code MinecraftServer.tickServer} HEAD, and {@code IntegratedServer.tickServer} does not
     * call its superclass at all while paused -- it calls {@code tickPaused()} and returns. So on
     * a paused single-player world the queue filled and nothing ever emptied it: no chunk was
     * given a ticket, the pre-gen made no progress, and because nothing threw, the log was silent
     * (mod_support #17, regression from 3.3.0's safe point).
     *
     * <p><b>Draining here is safe precisely because the server is paused.</b> The safe point exists
     * so ticket mutations never land during {@code tickChunks}' walk of the simulation chunk
     * tracker -- and while paused, {@code tickChunks} does not run at all. What the drain queues is
     * still propagated the same way it was before 3.3.0: the outer {@code runServer} loop keeps
     * pumping {@code ServerChunkCache.MainThreadExecutor.pollTask()}, whose first act is
     * {@code runDistanceManagerUpdates()}.
     *
     * <p>Server thread only.
     */
    void chunksmith$drainTicketSafePointNow();

    /**
     * True only while the safe-point queue is being drained, on the server thread.
     *
     * <p>The one condition under which chunk-ticket work may be done inline instead of queued.
     */
    boolean chunksmith$onTicketSafePoint();

    /**
     * Smoothed mean milliseconds-per-tick of the server main thread, sampled only
     * while a generation task is active. Used as the primary I/O-throttle signal.
     * A value at or near 50 ms means the server is holding a full 20 TPS; higher
     * means it is falling behind (the direct symptom of I/O saturation).
     */
    double chunksmith$getMillisPerTick();
}
