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
     * Whether ChunkSmith registers itself as Distant Horizons' world-generator override, serving DH
     * from the CSLOD store.
     *
     * <p>OPT-IN, default false, and deliberately so: overriding DH's generator means DH STOPS
     * generating for itself. Pregenerated area appears instantly; everything else returns no data.
     * That is right for a world you have pregenerated and wrong for one you have not.
     */
    boolean isLodDhOverrideEnabled();

    void reload();
}
