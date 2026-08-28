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

    /** Target tick time (ms/tick) the throttle steers toward: concurrency falls above it, rises below. */
    double getThrottleTargetMspt();

    /**
     * Absolute per-chunk latency backstop (ms): one slow chunk load backs off regardless of tick health.
     * Catches a pure I/O stall, and is the only signal on platforms that cannot report tick time.
     */
    long getThrottleMaxChunkMillis();

    /**
     * Max chunk writes queued to disk before dispatch stops until the backlog drains (hysteresis: resumes
     * at half). Bounds the deferred-write backlog so generation cannot outrun disk throughput. 0 disables.
     */
    long getThrottleMaxQueuedWrites();

    /**
     * Max chunks THIS RUN may ADD to the server's resident set before dispatch stops (hysteresis: resumes at
     * half). 0 disables. Delta, not absolute: 3.5.0 gated on the absolute number and tripped on servers whose
     * ordinary resident set was already near the cap -- the gate closed on somebody else's chunks and never
     * opened. Set it above the largest sweep frontier a run needs (roughly 16x the selection radius in
     * chunks, plus {@link #getPregenSettleMaxHeld()}) and below what the heap can hold. See
     * {@code ChunkResidency}.
     */
    long getThrottleMaxAddedChunks();

    /**
     * Heap usage, as a percentage of {@code -Xmx}, at which generation stops dispatching until the heap
     * drains. 0 disables. The backstop the chunk counters could not be: every other bound counts a PROXY,
     * and a chunk is worth wildly different amounts of heap depending on the entities and block entities
     * that came with it. Confirmed over several consecutive samples so ordinary uncollected garbage cannot
     * trip it, and resumed only once there is real headroom again.
     */
    long getThrottleMaxHeapPercent();

    /**
     * How many extra milliseconds per tick a pre-gen may ADD to what the server already costs. 0 falls back
     * to steering on {@link #getThrottleTargetMspt()} alone.
     *
     * <p>An absolute target cannot work on a busy server. Measured live: the tick cost 74.9 ms with the
     * pre-gen PAUSED against a configured target of 75, so the governor never saw a healthy tick, pinned
     * dispatch at its floor, and throttled the run to 2 chunks/sec while the run itself cost 10 ms. The
     * effective target is therefore {@code max(throttleTargetMspt, baseline + this)}, the baseline being the
     * tick cost with nothing in flight -- what Chunksmith COSTS, not whether the server is healthy.
     */
    long getThrottleTickBudgetMillis();

    /**
     * Tick time reserved for EACH online player, taken out of Chunksmith's own allowance. A rising baseline
     * already stops Chunksmith making things worse but gives the player nothing back; this yields, so an
     * empty server gets the full allowance and a populated one is actively given room.
     */
    long getThrottlePlayerReserveMillis();

    /**
     * Absolute tick cost the run will never steer past, whatever the measured baseline says. 0 disables.
     * Every other bound here is RELATIVE, and relative bounds move with the thing they protect against:
     * observed live, a 163.9 ms baseline produced a 238.9 ms target, steering toward about 4 TPS with
     * nothing objecting, because the heap gate was under its threshold and auto-pause compares against this
     * very target. Past this figure the server is not playable whoever is to blame, so the run yields. This
     * is the one bound that must not be relative.
     */
    long getThrottleCeilingMillis();

    /**
     * Pause a run when the server cannot sustain it, and resume it when the server recovers. ON by default
     * (maintainer decision, 2026-08-20): a gated pre-gen on an overloaded server does not stop, it stutters
     * -- measured at 60 chunks in two minutes, which looks exactly like a hang.
     */
    boolean isAutoPauseEnabled();

    /**
     * How long the server must stay in a state -- bad, then good -- before auto-pause acts. Both directions:
     * pausing on the first bad second would stop a run for a passing autosave, and resuming on the first
     * good second would restart it into the same wall.
     */
    int getAutoPauseGraceSeconds();

    /**
     * Whether ChunkSmith emits LOD data for the chunks it generates -- a TRISTATE, not a boolean. Default
     * {@link LodMode#AUTO}: ON when an LOD renderer (Distant Horizons, voxy, or a voxy fork) is present in
     * the JVM, and ON on a dedicated server, which exists to serve the store to Chunksmith-Client players.
     * An explicit {@code true} or {@code false} is an operator decision and is NEVER overridden. The
     * resolution lives in {@code LodSupport}, which has the loader's mod-loaded check and the running server.
     */
    LodMode getLodMode();

    /**
     * Max items queued in the LOD sink before the throttle backs off dispatch. 0 disables. Voxy's ingest
     * queue is UNBOUNDED and its ingest call never reports saturation, so without this governor a fast
     * pregen can outrun LOD ingestion and drive the heap into an OOM.
     */
    long getThrottleMaxLodQueue();

    /**
     * How many chunk requests Chunksmith keeps in flight at once -- the pipeline's WIDTH, and on a healthy
     * server what actually sets the rate. A chunk request spends almost all of its life WAITING: vanilla
     * walks it up through its generation statuses roughly a hop per tick, so per-chunk latency runs over a
     * second even when nothing is busy. Measured on a dedicated server (2026-08-20) at 40 percent CPU across
     * 8 cores, with the server thread spending 0.2ms of a 25ms allowance on us, dispatch was pinned at its
     * cap for the whole run while every other governor read "idle". Costs memory roughly linearly, which the
     * heap and residency gates are there to catch. Previously reachable only via the
     * {@code chunksmith.maxWorkingCount} system property, still honoured as this key's DEFAULT so existing
     * launch scripts keep working.
     */
    long getDispatchMaxConcurrent();

    /**
     * Keep a generated chunk loaded until its neighbours exist, so other mods can act on it. ON by default;
     * turn it OFF for a pure terrain pregen, since holding the sweep frontier costs memory and a little
     * throughput and buys nothing if nothing is listening. See {@code ChunkSettleWindow}.
     */
    boolean isPregenSettleEnabled();

    /**
     * Extra ticks to keep a chunk after its neighbourhood is complete, so a mod that acts a tick or two
     * after the chunk appears still finds it. Only meaningful when {@link #isPregenSettleEnabled()}.
     */
    long getPregenSettleDelayTicks();

    /**
     * Window radius in CHUNKS for the settle sweep. Sized to the biggest footprint another mod might want to
     * build: a Millenaire village wants 90 blocks, which is six chunks, so seven gives it room. Larger costs
     * more memory and disk reads per stop; smaller silently stops helping the bigger structures.
     */
    int getPregenSettleRadius();

    /**
     * Hard ceiling on how many chunks the settle window may hold open at once. 0 means unbounded.
     *
     * <p><b>Read this as a memory setting.</b> A held chunk keeps a FULL-level ticket and vanilla propagates
     * that level outward ring by ring, so ONE held ticket keeps roughly a neighbourhood -- measured at about
     * 25 resident chunks per held ticket during a pre-gen. A cap of 8192 is not "8192 chunks", it is closer
     * to two hundred thousand. The frontier is self-bounding only while every held chunk eventually gets all
     * nine neighbours; chunks beside SKIPPED ground never do, so a resumed world strands them, and past this
     * cap the oldest held chunk is released early -- age being the evidence its neighbourhood is not coming.
     */
    long getPregenSettleMaxHeld();

    /** Turn the settle window on or off and PERSIST it: a run may outlive several restarts. */
    void setPregenSettleEnabled(boolean enabled);

    /** Set the post-neighbourhood delay in ticks and persist it, clamped to the range the getter enforces. */
    void setPregenSettleDelayTicks(long ticks);

    /** Set the settle sweep radius in chunks and persist it. Clamped like the other settle setters. */
    void setPregenSettleRadius(int radius);

    /** Set the settle frontier cap and persist it. Clamped like the other settle setters. */
    void setPregenSettleMaxHeld(long maxHeld);

    /**
     * Whether this platform can honour the settle settings at all. False on Bukkit, which does not manage
     * chunk tickets itself: there is nothing to hold open, so the setting would be a lie rather than a
     * no-op, and the command reports that instead of pretending to have set something.
     */
    default boolean isPregenSettleSupported() {
        return true;
    }

    /**
     * Whether ChunkSmith registers as Distant Horizons' world-generator override, serving DH from the CSLOD
     * store. OPT-IN, default false: overriding DH's generator means DH STOPS generating for itself, so
     * pregenerated area appears instantly and everything else returns no data.
     */
    boolean isLodDhOverrideEnabled();

    /**
     * The TCP port the LOD backchannel binds, or 0 to derive it from the game port.
     *
     * <p>0 means {@code gamePort + 1}, right on a machine you control and wrong on a managed host, which
     * hands out a fixed set of ports and will not give you the one adjacent to your game port; before this
     * key those servers could not use the backchannel at all (mod_support #19). An explicit port is never
     * second-guessed. Changing it does NOT require a restart and clients need no matching setting -- the
     * port is advertised to each client on connect.
     */
    int getLodBackchannelPort();

    void setLanguage(String language);

    void setContinueOnRestart(boolean continueOnRestart);

    void setForceLoadExistingChunks(boolean forceLoadExistingChunks);

    void setIoThrottleEnabled(boolean enabled);

    void setThrottleTargetMspt(double mspt);

    void setThrottleMaxChunkMillis(long millis);

    void setThrottleMaxQueuedWrites(long writes);

    void setThrottleMaxAddedChunks(long chunks);

    void setThrottleMaxHeapPercent(long percent);

    void setThrottleTickBudgetMillis(long millis);

    void setThrottlePlayerReserveMillis(long millis);

    void setThrottleCeilingMillis(long millis);

    void setAutoPauseEnabled(boolean enabled);

    void setAutoPauseGraceSeconds(int seconds);

    void setThrottleMaxLodQueue(long items);

    void setDispatchMaxConcurrent(long chunks);

    void setLodMode(LodMode mode);

    void setLodDhOverrideEnabled(boolean enabled);

    /**
     * Set the backchannel port and persist it; 0 restores the derived {@code gamePort + 1}. Persisting is
     * the point -- a port re-applied after every restart would not solve the problem this key exists for.
     */
    void setLodBackchannelPort(int port);

    void reload();
}
