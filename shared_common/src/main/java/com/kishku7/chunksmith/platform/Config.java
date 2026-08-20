package com.kishku7.chunksmith.platform;

import java.nio.file.Path;

public interface Config {
    Path getDirectory();

    int getVersion();

    String getLanguage();

    boolean getContinueOnRestart();

    boolean isForceLoadExistingChunks();

    boolean isSilent();

    void setSilent(boolean silent);

    int getUpdateInterval();

    void setUpdateInterval(int updateInterval);

    boolean isIoThrottleEnabled();

    /**
     * Target server tick time (ms/tick) the throttle steers toward. The throttle reduces
     * concurrency when the smoothed tick time rises above this and increases it when the
     * server is comfortably keeping up.
     */
    double getThrottleTargetMspt();

    /**
     * Absolute per-chunk latency backstop (ms). A single chunk load taking longer than
     * this triggers an immediate back-off regardless of tick health -- catches a pure I/O
     * stall, and is the only signal on platforms that cannot report tick time.
     */
    long getThrottleMaxChunkMillis();

    /**
     * Maximum chunk writes allowed to queue to disk before the throttle stops dispatching
     * new chunks until the backlog drains (hysteresis: resumes at half this value). Bounds
     * the deferred-write backlog so generation cannot outrun disk throughput. 0 disables.
     */
    long getThrottleMaxQueuedWrites();

    /**
     * Maximum chunks THIS RUN may ADD to the server's resident set before the throttle stops
     * dispatching, until enough have unloaded (hysteresis: resumes at half). 0 disables.
     *
     * <p>Measured against the residency recorded when the run STARTED, not against an absolute count.
     * 3.5.0 gated on the absolute number and tripped on servers whose ordinary resident set was already
     * near the cap -- the gate closed on somebody else's chunks and never opened. What we can be
     * responsible for is what we added. See {@code ChunkResidency}.
     *
     * <p>Set it above the largest sweep frontier a run legitimately needs (roughly 16x the selection
     * radius in chunks, plus {@link #getPregenSettleMaxHeld()}) and below what the heap can hold.
     */
    long getThrottleMaxAddedChunks();

    /**
     * Heap usage, as a percentage of {@code -Xmx}, at which generation stops dispatching until the
     * heap drains. 0 disables.
     *
     * <p>The backstop the chunk counters could not be. Every other bound in this mod counts a PROXY --
     * queued writes, LOD queue depth, resident chunks, chunks added -- and a chunk is worth wildly
     * different amounts of heap depending on the entities and block entities that came with it. What
     * actually ends a pregen badly is running out of memory, so this measures memory. Confirmed over
     * several consecutive samples so ordinary uncollected garbage cannot trip it, and resumed only
     * once there is real headroom again.
     */
    long getThrottleMaxHeapPercent();

    /**
     * Pause a run when the server cannot sustain it, and resume it when the server recovers.
     *
     * <p>ON by default (maintainer decision, 2026-08-20). A gated pre-gen on an overloaded server
     * stop, it stutters -- measured at 60 chunks in two minutes, which looks exactly like a hang and
     * keeps the server under load for nothing. Stopping with a stated reason is better, and starting
     * again by itself costs the operator nothing. Turn it off to have a run push on regardless.
     */
    boolean isAutoPauseEnabled();

    /**
     * How long the server must stay in a state -- bad, then good -- before auto-pause acts on it.
     *
     * <p>Applies to BOTH directions. Pausing on the first bad second would stop a run for a passing
     * autosave; resuming on the first good second would restart it into the same wall.
     */
    int getAutoPauseGraceSeconds();

    /**
     * Whether ChunkSmith emits LOD data for the chunks it generates -- a TRISTATE, not a boolean.
     *
     * <p>Default {@link LodMode#AUTO}: LOD generation turns itself ON when an LOD renderer (Distant
     * Horizons, voxy, or a voxy fork) is present in the JVM, and ON on a dedicated server, which
     * exists to serve the store to Chunksmith-Client players. An explicit {@code true} or
     * {@code false} in the config is an operator decision and is NEVER overridden.
     *
     * <p>The resolution itself lives in {@code LodSupport} -- it needs the loader's mod-loaded check
     * and the running server, neither of which the config layer has.
     */
    LodMode getLodMode();

    /**
     * Maximum items allowed to queue in the LOD sink before the throttle backs off dispatch.
     *
     * <p>Voxy's ingest queue is UNBOUNDED and its ingest call never reports saturation, so without
     * this governor a fast pregen can outrun LOD ingestion and drive the heap into an OOM. 0 disables.
     */
    long getThrottleMaxLodQueue();

    /**
     * Keep a generated chunk loaded until its neighbours exist, so other mods can act on it.
     *
     * <p>ON by default. Turn it OFF for a pure terrain pregen with no mods that build on newly generated
     * chunks: holding the sweep frontier costs some memory and a little throughput, and buys nothing if
     * nothing is listening. See {@code ChunkSettleWindow} for what "the frontier" means here.
     */
    boolean isPregenSettleEnabled();

    /**
     * Extra ticks to keep a chunk after its neighbourhood is complete, so a mod that acts a tick or two
     * after the chunk appears still finds it. Only meaningful when {@link #isPregenSettleEnabled()}.
     */
    long getPregenSettleDelayTicks();

    /**
     * Window radius in CHUNKS for the settle sweep -- how much ground around a point is loaded together.
     *
     * <p>Sized to the biggest footprint another mod might want to build: a Millenaire village wants 90
     * blocks, which is six chunks, so seven gives it room. Larger costs more memory per stop and more
     * disk reads; smaller silently stops helping the bigger structures.
     */
    int getPregenSettleRadius();

    /**
     * Hard ceiling on how many chunks the settle window may hold open at once. 0 means unbounded.
     *
     * <p><b>Read this as a memory setting, because that is what it is.</b> A held chunk keeps a
     * FULL-level ticket, and vanilla propagates that level outward ring by ring, so ONE held ticket
     * keeps roughly a neighbourhood -- measured at about 25 resident chunks per held ticket during a
     * pre-gen. The number here is tickets; the cost is tickets times that halo. A cap of 8192 is not
     * "8192 chunks", it is closer to two hundred thousand.
     *
     * <p>The neighbourhood rule alone bounds the frontier only while every held chunk eventually gets
     * all nine of its neighbours; chunks beside SKIPPED ground never do, so a resumed world strands
     * them for the whole run. Past this cap the oldest held chunk is released early -- age being the
     * evidence that its neighbourhood is not coming. Only meaningful when
     * {@link #isPregenSettleEnabled()}.
     */
    long getPregenSettleMaxHeld();

    /**
     * Turn the settle window on or off and PERSIST the change.
     *
     * <p>Persisted deliberately: settle is tuned for a pregen run that may outlive several restarts, and a
     * setting you had to re-apply on every boot would be worse than no command at all.
     */
    void setPregenSettleEnabled(boolean enabled);

    /**
     * Set the post-neighbourhood delay in ticks and persist it. Clamped to the same range the getter
     * enforces, so a command can never write a value the loader would reject.
     */
    void setPregenSettleDelayTicks(long ticks);

    /**
     * Set the settle sweep radius in chunks and persist it. Clamped like {@link #getPregenSettleDelayTicks()}.
     */
    void setPregenSettleRadius(int radius);

    /** Set the settle frontier cap and persist it. Clamped like the other settle setters. */
    void setPregenSettleMaxHeld(long maxHeld);

    /**
     * Whether this platform can honour the settle settings at all.
     *
     * <p>False on Bukkit, which does not manage chunk tickets itself -- there is nothing to hold open, so
     * the setting would be a lie rather than a no-op. The command reports that instead of pretending to
     * have set something.
     */
    default boolean isPregenSettleSupported() {
        return true;
    }

    /**
     * Whether ChunkSmith registers itself as Distant Horizons' world-generator override, serving DH
     * from the CSLOD store.
     *
     * <p>OPT-IN, default false, and deliberately so: overriding DH's generator means DH STOPS
     * generating for itself. Pregenerated area appears instantly; everything else returns no data.
     * That is right for a world you have pregenerated and wrong for one you have not.
     */
    boolean isLodDhOverrideEnabled();

    void setLanguage(String language);

    void setContinueOnRestart(boolean continueOnRestart);

    void setForceLoadExistingChunks(boolean forceLoadExistingChunks);

    void setIoThrottleEnabled(boolean enabled);

    void setThrottleTargetMspt(double mspt);

    void setThrottleMaxChunkMillis(long millis);

    void setThrottleMaxQueuedWrites(long writes);

    void setThrottleMaxAddedChunks(long chunks);

    void setThrottleMaxHeapPercent(long percent);

    void setAutoPauseEnabled(boolean enabled);

    void setAutoPauseGraceSeconds(int seconds);

    void setThrottleMaxLodQueue(long items);

    void setLodMode(LodMode mode);

    void setLodDhOverrideEnabled(boolean enabled);

    void reload();
}
