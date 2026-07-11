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
     * Whether ChunkSmith emits LOD data for the chunks it generates (currently: into voxy, when voxy
     * is installed). OPT-IN, default false: LOD ingestion measurably slows a pregen (~2.4x in the
     * 26.1.2 spike), so it must never be switched on behind the operator's back.
     */
    boolean isLodEnabled();

    /**
     * Maximum items allowed to queue in the LOD sink before the throttle backs off dispatch.
     *
     * <p>Voxy's ingest queue is UNBOUNDED and its ingest call never reports saturation, so without
     * this governor a fast pregen can outrun LOD ingestion and drive the heap into an OOM. 0 disables.
     */
    long getThrottleMaxLodQueue();

    void reload();
}
