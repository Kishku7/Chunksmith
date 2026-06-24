# Plugin API Parity Audit (branch 26)

Question: of the capabilities the Fabric/NeoForge mod gets through `shared_minecraft`
mixins/accessors, does any Bukkit/Paper/Folia API let the Bukkit plugin achieve a
*similar outcome* -- even via a redesigned (non-mixin) approach?

Short answer: the gap is architectural (mixin into `net.minecraft` internals vs a public
server API), but for the practical outcomes the plugin already carries redesigned API-based
equivalents for almost everything. Two deep-internal items have no API path -- and both are
either moot on Paper or already substituted by a proxy signal.

## Matrix

| # | Mod capability (mixin) | Plugin status | API path / redesign |
|---|------------------------|---------------|---------------------|
| 1 | Keep-awake / idle-pause suppression (`MinecraftServerMixin` resets `emptyTicks` each tick while generating; scoped to the run) | DONE on non-Paper | `disablePauseWhenEmptySeconds()` rewrites `server.properties` `pause-when-empty-seconds -> 0`. Outcome-equivalent but global + persistent, not scoped. **Guarded off on Paper** (`!Paper.isPaper()`). |
| 2 | Tick-health throttle signal -- EWMA ms/tick (`MinecraftServerMixin` -> `chunksmith$getMillisPerTick`) | DONE | `BukkitServer.getMillisPerTick()` returns `Server.getAverageTickTime()` (Spigot/Paper). Shared `GenerationTask` consumes whatever signal the platform supplies. |
| 3 | Async chunk generation to FULL (`ServerChunkCacheMixin.getChunkFutureMainThread`, `ChunkMapMixin.readChunk`) | DONE | `BukkitWorld.getChunkAtAsync` -> Paper `world.getChunkAtAsync(x,z,true)`. |
| 4 | Aggressive chunk unload / chunk-system housekeeping (force distance-manager update + chunkMap tick + entity-manager tick to free pre-gen chunks immediately) | PARTIAL | `add/removePluginChunkTicket` -- removing the ticket lets the server unload on its own cadence. No API to force the immediate sweep the mixin does. |
| 5 | Worldgen-fault attribution -- far-chunk overreach + structure faults (`WorldGenRegionMixin`/`StructureStartMixin`/`BlockAttachedEntityMixin` -> reporters with exact chunk attribution) | DONE (best-effort) | `WorldgenOverreachLogFilter` (Log4j2 filter) captures vanilla's "Detected setBlock in a far chunk" log line and routes it to the same reporters; 1 Hz scheduler tick drives rollup. Lower fidelity (no precise chunk coords; needs Log4j2). |
| 6 | Region-threaded scheduling | DONE (plugin-only bonus; mod is single-threaded) | Folia `RegionScheduler` / `GlobalRegionScheduler`. |
| 7 | Entity-retention defect fix -- skip the redundant disk read for fresh chunks (`PersistentEntitySectionManagerMixin` + `EntityStorage`/`SimpleRegionStorage`/`IOWorker`/`RegionFileStorage` accessors read the region offset table directly to avoid a pointless load round-trip; prevents entity pile-up / RAM climb / save-stall crash on big pre-gen) | NONE | **No API path.** No Bukkit/Paper API exposes `PersistentEntitySectionManager` or the storage internals. Also likely moot on Paper: Paper ships the Moonrise chunk system (the mod detects it via `ENABLE_MOONRISE_WORKAROUNDS`), which rewrites entity/chunk management -- the vanilla bug this targets is a vanilla-chunk-system bug. |
| 8 | Direct I/O write-queue backpressure -- exact `pendingWrites` depth (`IOWorkerAccessor`) | SUBSTITUTED | No API for the queue depth. Plugin uses tick-time (`getAverageTickTime`) as the disk-saturation proxy: when the disk saturates, ticks lengthen. |

## Bottom line

- 5 of 8 capabilities already have working redesigned API equivalents in the plugin (#1-3, #5, plus the #6 Folia bonus).
- #4 is partial (ticket-based unload; cannot force-sweep).
- #7 has no API path and is probably unnecessary on Paper (Moonrise).
- #8 has no API for the raw signal but is already substituted by the tick-time proxy.

There is no mod-only feature that is simultaneously (a) still missing from the plugin,
(b) materially important on Paper/Folia, and (c) reachable through an unused public API.
The mixin-vs-API architecture gap is real, but the practical outcomes are covered.

## One concrete actionable -- keep-awake on Paper

`disablePauseWhenEmptySeconds()` is guarded by `if (!Paper.isPaper())`, so on Paper the
plugin does NOT force `pause-when-empty-seconds=0`. The code does not document why. Our
entire smoketest fleet is Paper/Folia, so this is directly testable:

- Start a Paper pre-gen with no players online and `pause-when-empty-seconds > 0`.
- If the server idle-pauses mid-generation, Paper needs the same treatment (or a Paper-side
  equivalent) and we should extend keep-awake to cover Paper.
- If it does not pause (Paper keeps the server "non-empty" while chunk tickets are held, or
  Paper handles idle differently), the guard is correct and no change is needed.

This is the single keep-awake item worth verifying given Dave's note that upstream proved the
feature is achievable on the server side.

---

## Verification results (2026-06-24, source-backed)

### Keep-awake (#1): Paper does NOT need it -- guard is correct
Empirical test on Paper 26.1.2: set `pause-when-empty-seconds=10`, no players, ran a
radius-500 Chunksmith pre-gen. Result: COMPLETED -- the server stayed awake through the
entire no-player generation and the task finished normally. So Paper does not idle-pause
while generation work is pending, and the plugin's `if (!Paper.isPaper())` skip of
`disablePauseWhenEmptySeconds()` is correct, not a bug. No change needed.

### Chunk unload + entity retention (#4, #7): Paper/Moonrise already does it, better
Decompiled the patched `paper-26.1.2.jar` and `paper-26.2.jar` (vineflower). The Moonrise
chunk-system classes `NewChunkHolder`, `ChunkHolderManager`, `ChunkTaskScheduler` are
BYTE-IDENTICAL between 26.1.2 and 26.2, so this applies to both.

Entity retention (#7): Moonrise replaces the vanilla `PersistentEntitySectionManager`
subsystem with `NewChunkHolder` + `ChunkEntitySlices` + `EntityLookup`. Entity NBT is
loaded via an async `pendingEntityChunk` callback and unloaded via an async
`entityDataUnload` task (`unloadStage1/2`). There is NO blocking read-before-free -- the
exact vanilla defect our `PersistentEntitySectionManagerMixin` works around. On Paper the
defect structurally does not exist, so that mixin is moot.

Forced unload / housekeeping (#4): Moonrise drains a dedicated `ChunkUnloadQueue` via
`processUnloads()` every tick, unloading a BOUNDED fraction per pass
(`configMinChunkUnloadFraction` / `configMinChunkUnloadCount`), and saves dirty chunks with
a separate bounded incremental `autoSave()` (`configMaxAutoSavePerTick`). Chunk writes are
async (`saveChunk` -> `Completable` -> `asyncCompleteUnloadTask`). This is prompt AND
throttled -- strictly better than our vanilla-targeted force-sweep
(`invokeRunDistanceManagerUpdates` + `chunkMap.tick`), which cannot bound its own work.

Conclusion: on Paper/Folia the plugin correctly needs none of #1/#4/#7. Those mixins exist
solely because vanilla Fabric/NeoForge servers run the unpatched chunk system. Decompiled
source kept at `_tools/paperdec/{26.1.2,26.2}/src/` for reference.
