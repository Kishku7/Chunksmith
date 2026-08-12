# Chunksmith Changelog

## [Unreleased]

## [3.2.4] - 2026-08-11

### Added

- **`/cs set` -- every setting is now readable and writable in-game.** A setting you can only change
  by editing a file and restarting is not much use on a running server. Only two of the thirteen
  settings had a command; the rest, including the three new settle keys, could not be changed on a
  running server at all.
  - `/cs set` lists every setting with the value in force, `/cs set <name>` shows one, and
    `/cs set <name> <value>` changes it and saves it immediately.
  - Values with a legal range are clamped **as they are written**, not only as they are read, so the
    file can never hold a number the mod would refuse to honour. The command reports the value that
    was actually stored rather than echoing what you typed -- ask for a settle radius of 40 and it
    tells you it is 16.
  - A value that cannot be understood at all -- a word where a number belongs, a language that is
    not shipped -- is refused, rather than quietly becoming a default.
  - `/cs silent` and `/cs quiet` still work and are unchanged. Both now PERSIST, which they did not
    before: they used to change the running value and forget it at the next restart.
  - On Paper/Folia the three `pregenSettle*` settings report that they do not apply rather than
    accepting a value that is ignored. Bukkit does not manage chunk tickets, so there is no window
    to hold open.

### Fixed
- **Other mods can build on freshly pregenerated land again.** A pregen added a chunk ticket,
  generated the chunk and dropped the ticket the instant the future completed. For pure terrain that
  is exactly right and it is what keeps a run's memory flat -- but a mod that reacts to "a new chunk
  appeared" and does its work on a later server tick found the chunk, and everything around it,
  already unloaded. It could not build, so it deferred, and it kept deferring for the whole run.
  Reported against Millenaire (mod_support #14), whose villages simply never appeared in
  pregenerated land: measured on a NeoForge 1.21.1 pregen, **309 spawn attempts and 309 deferrals,
  zero villages**.
  - A chunk's ticket is now held until **all eight of its neighbours have also been generated**,
    plus a short delay. What is held is therefore the sweep frontier and nothing else -- it is
    bounded by the shape of the pattern rather than by a guessed number, and it shrinks to nothing
    as the run finishes.
  - The rule is deliberately about chunks, not about any particular mod. Nothing in Chunksmith knows
    Millenaire's name; anything that builds on newly generated chunks benefits, and anything that
    does not is unaffected.
  - **On by default.** `pregenSettle: false` in `config/chunksmith/config.json` restores the old behaviour
    exactly -- release inline, allocate nothing -- which is the right setting for a pure terrain
    pregen with no such mods installed, where holding the frontier costs memory and a little
    throughput and buys nothing. `pregenSettleDelayTicks` (default 40, i.e. two seconds) tunes how
    long a chunk lingers after its neighbourhood closes, and `pregenSettleRadius` (default 7 chunks,
    maximum 16) sets how much ground the trailing sweep loads together -- sized to the largest
    footprint a mod is likely to want at once.
  - Note for upgrades: an existing config is never rewritten, so these keys will not appear in a
    config written by an earlier version. They take their defaults regardless; delete
    `config/chunksmith/config.json` and restart to get a fresh one that lists them.
- **LOD data is no longer re-injected into the renderer on every single world join.** Which
  regions had already been handed to voxy / Distant Horizons was remembered only in memory, and
  that memory was thrown away on disconnect -- so every join re-decoded and re-pushed the entire
  in-range store, whether or not the renderer already had every bit of it. On a large pregenerated
  world that is minutes of CPU on a background thread at every single join, for terrain that was
  already drawn. Reported by Maker261 (mod_support #15) on a two-core machine, where it is
  impossible to miss.
  - The claim set is now written to disk as `.injected`, one per dimension, next to the region
    files it describes -- the same `x,z=token` line format and the same atomic `.part`+move
    discipline the download manifest already uses. A join now starts from what the last session
    actually injected instead of from nothing.
  - A region whose token has MOVED is still re-injected. That is the whole point of tracking the
    token rather than the coordinates, and it is what keeps a pregen that is still growing the
    region under the player's feet from freezing at whatever it was when they first joined.
  - The file records WHICH renderers it was written for. Install Distant Horizons alongside an
    existing voxy setup and the epoch no longer matches, so everything is injected once into the
    renderer that has never seen it, rather than being skipped forever.
  - `reinject-on-join` (new, in `config/chunksmith-lod.properties`, default `false`) forces a full
    re-injection for one session. It is the escape hatch for the case the epoch cannot see: a
    player who deletes or resets their renderer's own database still holds a `.injected` that
    honestly describes what we sent, and we would otherwise believe them. Deleting the `.injected`
    files does the same thing.

## [3.2.3] - 2026-08-04

### Added
- **Pregeneration now verifies its own work.** A chunk counted as "generated" was only ever a
  claim: the counter is incremented when the load future completes, and a future can complete
  having done nothing at all -- which is exactly how 3.2.2's C2ME bug went unnoticed for two
  releases while every counter and log line reported success. At the end of a run Chunksmith now
  takes a spread of up to 32 chunks it says it generated and asks the world whether they are
  actually on disk. If any are missing it says so loudly, with the count and example coordinates,
  instead of reporting a clean finish. Costs at most 32 status reads, once, per run.
  - Deliberately quiet when it cannot get an answer (busy or shutting-down server): only a
    definite "not on disk" is ever reported. A check that cries wolf gets ignored.
- The end-of-run drain now happens for every task rather than only LOD-enabled ones, so a task no
  longer declares itself finished while its own dispatches are still outstanding. Previously a
  non-LOD run could under-report by up to the dispatch limit.

### Changed
- **MC 26.3 rebuilt onto `26.3-snapshot-7`** (was snapshot-6), Fabric API `0.156.2+26.3`,
  `pack_format` **95** (was 94). Every 26.3 snapshot so far has bumped the resource pack format by
  exactly one, so each build is snapshot-exclusive by design -- `3.2.2+26.3` remains available and
  listed for snapshot-6.

## [3.2.2] - 2026-08-04

### Fixed
- **Pregeneration silently generated nothing when C2ME was installed (Fabric, all versions).**
  Reported as mod_support #13 on Minecraft 1.21.1 Fabric: Chunksmith reported thousands of chunks
  generated, in effectively zero seconds, and no terrain was written. Every Fabric build from 3.2.0
  onward was affected on every Minecraft version, not only 1.21.1.

  Root cause: 3.2.0 propagated the C2ME ticket-race guard fleet-wide, which correctly skips the
  forced `runDistanceManagerUpdates()` call when C2ME is present. But that call is also what builds
  the `ChunkHolder` for the chunk ticket Chunksmith had just added, and the follow-up
  `getChunkFutureMainThread(x, z, FULL, create)` was still being passed `create = false`. With no
  holder and no permission to create one, it returned an immediately-completed FAILED `ChunkResult`
  ("unloaded chunk"). A failed `ChunkResult` is not an exception, so the completion callback saw a
  null throwable, released the ticket, and counted the chunk as finished. The result was a pregen
  that reported complete success at impossible speed while doing no work.

  Fix: when either C2ME or Moonrise is present, `getChunkFutureMainThread` is now allowed to create
  the holder itself (`create = true`). The forced distance-manager call stays skipped under C2ME, so
  the 3.1.5 ticket-race crash fix is preserved. Moonrise already used this exact mechanism for the
  same underlying reason.

  Verified by disk state rather than by Chunksmith's own progress counter: a fixed 4225-chunk
  selection now writes 4225 chunks at `minecraft:full` into the region files with C2ME installed,
  matching the no-C2ME control exactly. Before the fix the same selection wrote 49.

## [3.2.1] - 2026-08-03

### Added
- **Voxy LOD support on Fabric 1.21.1 and NeoForge 1.21.1**, via the `m3t4f1v3` fork of voxy
  (github.com/m3t4f1v3/voxy, `multiversion` branch). Upstream voxy has never published a build
  for either of these cells (Fabric 1.20.1/1.21.1, or any NeoForge line), so Chunksmith's voxy
  adapter previously compiled out entirely on both. The fork's NeoForge cell is a genuine native
  NeoForge port (its own mojmap-compiled entrypoints, not a Sinytra Connector repackage), which is
  what makes it compatible with Chunksmith's own mojmap-native NeoForge build -- the same
  Connector-repackage forks that were rejected before remain unusable, only this one fork's direct
  compile target is viable. `_codegen/compat.py`'s `has_voxy()` now recognizes both cells; the
  `_real` voxy adapter (`CsLodVoxyInjector`, `VoxyLodSink`, `VoxyTarget`, `VoxyRadius`) is compiled
  in on both, and both were verified end-to-end on the LOD functional render gate (server pregen,
  client join, region fetch + inject, EYEBALL-confirmed terrain render) before release.

### Scope
- **Only two jars change in this release**: `chunksmith-3.2.1+1.21.1.jar` (Fabric) and
  `chunksmith-3.2.1+1.21.1-neoforge.jar` (NeoForge). Every other cell is unchanged and remains at
  `3.2.0` -- there is nothing in this release for them to receive.

## [3.2.0] - 2026-08-03

### Added
- **Universal LOD generation on the Bukkit/Paper/Folia plugin.** The plugin line previously
  shipped with LOD generation hardcoded off; it now generates LOD data by default, matching
  the Fabric/Forge/NeoForge behavior, using the same pregen-time hook the mod side already
  used. No configuration needed -- `lod-enabled: false` still turns it off for admins who
  want pregen without LOD.

### Fixed
- **Housekeeping-hook stall under a large chunk-unload backlog (issue #11).** The shared
  per-tick housekeeping mixin (every Fabric/Forge/NeoForge cell) called
  `chunkMap.invokeTick(() -> true)`, discarding vanilla's real per-tick time budget
  (`haveTime`, already passed in and unused) and letting the unload loop run with no time
  limit every tick. A backlog of 13k+ chunks pinned the server thread for over 50 minutes
  CPU time on one reported world. Fixed by passing the real `haveTime` through -- shared
  plumbing, so every cell picks up the fix automatically. Load-tested 62.5 minutes
  continuous on the exact reported configuration (144,433 chunks processed, zero stalls)
  and boot-verified across the rest of the matrix.
- **Plugin: `IncompatibleClassChangeError` extracting LOD biome data on some Paper builds.**
  Bukkit's `Biome` type changed from a class to an interface across Paper API generations
  within the same Minecraft version line; a plugin jar compiled against one shape could
  throw at runtime against a server built against the other. Fixed by resolving the biome
  through the stable `org.bukkit.Keyed` interface instead of the concrete `Biome` type, and
  by making LOD extraction failures fail loud (logged) instead of silently doing nothing, so
  a bug in this class can't hide again.
- **C2ME compatibility guard was only wired into one Fabric cell.** A ticket-race workaround
  for running alongside the C2ME concurrency mod (added for Fabric 1.21.11 in 3.1.5) is now
  applied on every Fabric/Forge/NeoForge cell. No behavior change where C2ME is absent --
  Forge and NeoForge never set the detection flag that arms it.

### Changed
- **Version numbering reconciled fleet-wide.** Every cell (Fabric, Forge, NeoForge, and the
  plugin, across every supported Minecraft version) now reports `3.2.0`. Previously the
  plugin tracked `3.1`, most mod cells tracked `3.1.1`, and two cells (Fabric 26 and Fabric
  1.21.11) had drifted ahead to `3.1.4`/`3.1.5` on earlier fixes that never got a version
  bump recorded here.

## [3.1.5] - 2026-07-29

### Fixed
- **Fabric 1.21.11: C2ME chunk-ticket race that could crash the server** (`FabricWorld.getChunkAtAsync`).
  Every chunk request forced an immediate, synchronous `runDistanceManagerUpdates()` call right after
  adding a ticket, instead of letting the distance manager process tickets on its own once-per-tick
  cadence. Vanilla only ever runs that update once per tick from one place, so ticket-map mutation and
  iteration stay serialized; forcing it here re-entered that code far more often than vanilla ever
  would. C2ME rewrites the chunk ticket/distance manager onto its own concurrent scheduler, and this
  out-of-cadence forcing could trigger a race there -- observed as a server crash, NPE in
  `Long2ByteOpenHashMap` iteration ("this.wrapped" null) while ticking chunk tickets, reproduced on the
  "Zion" world after starting a Chunksmith pregen with C2ME installed.
- Chunksmith now detects C2ME at init (`PlatformCompat.ENABLE_C2ME_TICKET_COMPAT`, mod id `c2me`) and,
  when present, skips the forced call -- the newly added ticket is picked up by C2ME's own scheduler on
  its next pass instead of racing it. No behavior change when C2ME is absent. Scoped to the Fabric
  1.21.11 platform-adapter variant (`compat_platform.py` "a2e3c49d5235"); the detection flag itself is
  shared plumbing (harmless no-op on every other cell until wired into their variants too).

## [3.1.4] - 2026-07-28

### Changed
- **D16: the platform adapters are single-sourced.** The 8 per-cell adapter classes
  (`Fabric`/`NeoForge` x `Player`/`Sender`/`Server`/`World`) were the LAST hand-copied master in
  the mod -- **104 files across the 26 build cells**. They now live in
  `_codegen/compat_platform.py` and are materialised into each cell's `gen/` by `cog-gen.ps1`
  (new Step 3d), exactly as `Border` was in Step 3c. **Every cell's `src/main/java` is now empty:
  100% of Chunksmith's Java comes from the one cog source of truth.**
- The adapters carry real MC-era drift, so they are stored as WHOLE-FILE era variants plus a
  measured cell->variant map, which is the house pattern for this (cf. bank-vault's `compat_*.py`).
  FabricWorld had 9 distinct bodies across 10 cells and NeoForgeWorld 11 across 16; on 26 the
  `*World` classes are a structural fork (different interfaces implemented, extra fields, an extra
  ticket block), not a rename set, so inline predicates would have been the wrong shape.
- Exactly ONE genuine loader difference exists in the whole set -- `ChunksmithForge` vs
  `ChunksmithNeoForge` in `NeoForgeServer`. Everything else is version drift.

### Added
- `scripts/verify-platform.py` -- the D16 hash oracle. Default mode checks what
  `compat_platform.py` materialises against each cell's generated `gen/` tree; `--against-git <ref>`
  checks it against the pre-migration committed copies. Non-zero exit on any mismatch, so it can
  gate a build.

### Verified
- **Hash oracle, twice: 104/104 byte-identical** (line endings normalised -- git rewrites those on
  checkout anyway) against the pre-migration copies at the previous commit, and again against the
  freshly generated `gen/` trees after all 26 cells were regenerated.
- **All 26 cells rebuilt: 26 OK, 0 failures, 0 javac warnings.**

### Note
- An analysis pass flagged the missing `LodSupport.offer` hook in 18 of 26 cells as unintended
  drift, citing same-version Forge/NeoForge pairs that differ. **It is not drift.** Those 8 cells
  are exactly `compat.has_lod()` -- verified by running the predicate across the cell list against
  the hook's real locations, 8/8. Forge's only Distant Horizons line is 1.20.1, so Forge/1.21.1
  correctly has no hook. Recorded so the next pass does not "fix" the LOD curation into cells that
  cannot use it.

## [3.1.3] - 2026-07-28

### Changed
- **Fabric 26.3 cell moved to MC 26.3-snapshot-6** (from snapshot-5): fabric-api
  `0.155.3+26.3` -> `0.156.1+26.3`, `pack_format` `93` -> `94`, and the exclusive
  snapshot pin `depends.minecraft` `26.3-alpha.5` -> `26.3-alpha.6` (Fabric normalizes
  `26.3-snapshot-N` to `26.3-alpha.N`). The snapshot-5 build stays listed on Modrinth;
  this jar does not claim it.

### Notes
- **No source change required.** Snapshot-6 is a large MC release -- it rewrote the
  worldgen noise stack (`NormalNoise.NoiseParameters` deleted, `Registries.NOISE`
  retyped, `DensityFunction` gained an abstract `domainAxes()`, `ImprovedNoise` /
  `PerlinSimplexNoise` removed) and reworked the terrain render path
  (`ChunkSectionLayer.pipeline(boolean)`, `LevelRenderer.prepareChunkRenders` gained a
  flag, `DynamicUniforms` -> `DynamicGpuData`). Chunksmith references NONE of those
  symbols: it drives pregeneration through the server-facing chunk APIs only, so the
  overhaul does not reach it (verified by scanning all 346 source files).
- The 26.3 LOD functional gate remains untestable: Distant Horizons still ships no
  26.3 build upstream.

## [3.1.2] - 2026-07-21

Adds Minecraft 26.3-snapshot-5 (Fabric). The 26.3 Fabric jar is rebuilt against 26.3-snapshot-5 (Fabric API 0.155.3+26.3, pack_format 93). The mod loads and renders in-world on the snapshot's reworked GPU/shader ("renderpearl") pipeline with no source changes -- the LOD mixins and the Distant Horizons / voxy adapters apply cleanly. Verified in-world on the headless client harness. The 26.3 Fabric jar's dependency is pinned to 26.3-snapshot-5 exclusively, per the per-snapshot compatibility policy for the rendering line.

## [3.1] - 2026-07-16

Stable plugin release (Paper / Spigot / Folia) of the 3.1 line. The Bukkit plugin is a server-side pre-generator only -- it has no LOD/distant-terrain code (there is no plugin-side renderer), so it is functionally unchanged from the 3.1.0 betas. Rebuilt at 3.1 for the stable line across the 1.20.x, 1.21.x, and 26.x families.

## [3.1.1] - 2026-07-16

Stable release. This promotes the entire 3.1.0-beta line to a stable build -- no issues were reported on the betas -- and adds Minecraft 26.3-snapshot-4 (Fabric). All 29 mod jars (Fabric, NeoForge, Forge across the 1.20.x / 1.21.x / 26.x families) are rebuilt at 3.1.1.

Everything from the 3.1.0 betas is now stable:

- One jar does everything: Chunksmith-Client is merged in, so a single mod handles both server-side chunk pre-generation and the multiplayer distant-terrain (LOD) delivery -- no separate client mod to install. (3.1.0-beta-1)
- Fixed a main-thread memory blowup when serving LOD index requests, and added periodic client/server checksum sync. (3.1.0-beta-4)
- Fixed cross-dimension LOD leakage and delivery to players who joined before pre-generation had data. (3.1.0-beta-2 / beta-3)
- Forge 1.20.1: the LOD network channel is optional again, so a client without Chunksmith can join a Chunksmith Forge server. (3.1.0-beta-5)

### New in 3.1.1

- Minecraft 26.3-snapshot-4 support (Fabric); resource pack_format 92.

## [3.1.0-beta-5] - 2026-07-14

**On Forge 1.20.1, a player who did not have Chunksmith could no longer join a server that did.** The mod
is meant to be optional on the client -- it is a server-side pre-generator, and the client half only adds
distant terrain for players who want it. But since 3.x the server registered its `chunksmith:lod` network
channel as *required*, so Forge's login handshake refused any client that lacked it: the player was kicked
at join with "Connection closed - mismatched mod channel list". This build makes the channel optional
again, so a vanilla-Forge client joins a Chunksmith Forge server exactly as it did on 2.2.3. The server is
unchanged for players who do have Chunksmith -- the channel simply is not negotiated to those who do not.

This was a regression from the 3.x LOD channel and it affected **Forge only**. NeoForge was already correct
(its channel was marked optional during the 3.1.0-beta-1 client merge) and Fabric never forced the client
(its play channels are inherently permissive). Only the Forge cell was missed.

Only the **Forge 1.20.1** jar changed. Every other cell is byte-for-byte identical to 3.1.0-beta-4 and is
unaffected.

### Fixed

- **Forge 1.20.1: the LOD network channel is now optional.** Both accepted-version predicates on the
  `chunksmith:lod` `SimpleChannel` are wrapped in `NetworkRegistry.acceptMissingOr(...)`, which accepts the
  "channel absent" sentinel the FML login handshake sends for a client that does not have the channel. A
  bare version-equality predicate rejected that sentinel, which marked the channel required and made the
  server refuse the client. Mirrors the NeoForge cell's `.optional()`.

## [3.1.0-beta-4] - 2026-07-13

**Every time a player asked "what LOD terrain is near me?", the server read every region file in the store
and hashed its contents -- on the main server thread.** On a 340-region, 850 MB store that is **205 MB of
memory allocated per request**, several times a second, in blocks big enough that the garbage collector has
to handle each one as a special case. A live server ran out of memory doing it, and then took **67 minutes**
to shut down. It is fixed: the answer is now built from each file's timestamp and size, off the main thread,
and the same request allocates **18 KB** -- **11,808x less** -- with **zero** of those collector pauses where
there used to be 38.

**Also new: your client now notices new terrain on its own.** Every few minutes it and the server compare a
single small checksum; if they differ, the client pulls only what changed. You no longer have to relog, or
walk somewhere, to see terrain a running pre-generation has just finished.

> ### Read this before you update
>
> **The LOD network protocol changed (v1 -> v2). Your server and every player's client must BOTH be on
> `3.1.0-beta-4` or later.** A mismatched pair will not exchange LOD data -- both sides refuse it, both say
> so in the log, and nothing else breaks (no crash, no hang, no stuck downloads) -- but the distant terrain
> will simply not be there. There is no way around this: the number the two sides compare to decide "do I
> already have this region?" is exactly the thing that had to change, and an old client asking the new
> question would re-download your entire LOD store every five seconds, forever. We would have traded a memory
> problem for a bandwidth one.
>
> **Update the server and the clients together.** On the first join after updating, each client re-downloads
> the regions in its view radius once -- it has no record of what it can vouch for yet -- and then never
> again.

### Fixed

- **The LOD index no longer reads the store to build itself, and no longer runs on the server thread.** To
  tell a client which regions it should have, the server used to open every region file near that player,
  read all of it, and CRC32 the bytes -- 205 MB of allocation per request on a real store, 74-173 ms of the
  tick each time, and because the buffers are multi-megabyte the JVM allocates them out of a special
  "humongous" path that forces a collection. Over five minutes of a player walking around, that was **38
  garbage-collection pauses, all of them attributed to exactly this**. It is why one server climbed to 100%
  memory during a pre-generation and could not save its worlds afterwards. The freshness marker for a region
  is now derived from its **modification time and size** -- which is all the question ever needed, because
  the question is only ever "is this the same file I already sent you?" -- and the scan runs on a background
  thread, one outstanding scan per player, so the tick does no I/O at all. Same request: **18 KB, under a
  millisecond, zero humongous allocations, zero GC pauses.**
- **The client was doing the same thing to its own store, and nobody ever noticed.** On every index it read
  and hashed every region file it had, for the same reason. A server dies loudly and gets reported; a client
  with a big heap just stutters. It now records what the server told it about each region in a small manifest
  file beside the store, so the "do I already have this?" check is a lookup and one file-size stat.
- **The index can no longer be unbounded in size.** It was capped at 4,096 regions -- but a region can be
  7 MB, so that cap permitted a ~28 GB answer. It is now capped in **bytes** (2 GiB), and the scan is sorted
  nearest-first, so if a store is bigger than the cap what a client loses is the furthest terrain, and it
  gets that as it walks toward it.
- **A region that GREW is no longer downloaded and then thrown away.** The client remembered which regions it
  had drawn by their coordinates alone -- which answers "have I ever drawn this?", not "have I drawn *this
  version* of it?". A pre-generation does not only create new regions; it keeps growing the ones under you,
  for hours. The client would notice the change, fetch the bigger file, hand it to the renderer -- and the
  renderer's own bookkeeping would recognise the coordinates, drop the new data on the floor, and report
  success. You would have watched the far ring of terrain fill in while the ground under you stayed frozen at
  whatever it was when you joined. Regions are now remembered by coordinate *and* version.
- **A client on a too-old server is no longer left staring at an empty horizon in silence.** If you update
  your client before the server updates, the old server refuses your hello and answers nothing at all -- so
  the client has nothing to check a version against, and it used to note the silence only at debug level.
  You got no terrain and no explanation. It now says so plainly, once, in the log: no LOD data is on offer
  here, either because this server does not run Chunksmith (perfectly normal) or because it runs a version
  older than `3.1.0-beta-4`, and if you expected terrain then both ends need to be on the same version. It
  cannot tell those two cases apart -- an old server tells it nothing to tell them apart *with* -- so it
  names both instead of guessing. One line, once, and never silence.

### Added

- **Periodic checksum sync -- new terrain arrives while you stand still.** Every 300 seconds by default, the
  client asks the server for a one-line summary of the LOD regions in its view (a count and a single folded
  checksum) and compares it with its own. If they match, that is the end of it. If they do not, it pulls the
  index and fetches **only the difference**. No relog. No walking. It costs **22 bytes out and 34 bytes back**
  -- about 0.19 bytes per second per player -- and on the server it is roughly 86 syscalls and **not one byte
  of file content read**. One mechanism covers all three of "the server generated more", "a region I hold
  changed", and "I lost regions off my disk" (delete some and the next poll brings back exactly those).
- **`sync-interval-seconds` in `config/chunksmith-lod.properties`** (client-side; the file is written with
  defaults and comments on first run). Default **300**. **Values below 30 are clamped to 30** -- a config
  value is a suggestion, and a one-second poll must not become a denial of service against a server that is
  already busy pre-generating. No settings screen yet; that is `3.2`.

### Changed

- **`CsLodProtocol.VERSION` 1 -> 2.** Deliberate and unavoidable; see the notice above. The wire *layout* is
  unchanged -- what changed is the *meaning* of the index's freshness field (a CRC32 of the file's contents
  became an opaque timestamp+size token). Both ends check the version, both refuse a mismatch, and both name
  it in the log rather than failing quietly.
- **Moving a world between machines, or restoring it from a backup, now re-sends the LOD regions once.**
  Copying files changes their modification times without changing their contents, so every freshness token
  moves and connected clients re-fetch what is in their radius. That is the honest price of the fix, it is
  bounded, and it is once. The alternative failure -- a marker that says "unchanged" about a region that did
  change -- leaves a player looking at terrain that no longer exists with no mechanism that could ever
  correct it. A redundant download is a bandwidth bill; a stale region that is trusted is a bug you cannot
  see.

### Notes

- **This release rebuilds the eleven jars that carry the LOD feature** (Fabric 1.20.1 / 1.21.1 / 1.21.11 /
  26.1 / 26.2 / 26.3, NeoForge 1.21.1 / 1.21.11 / 26.1 / 26.2, Forge 1.20.1). The Bukkit/Paper/Folia plugin
  jars and the other mod jars have no LOD code path at all, so they are unchanged and remain at
  `3.1.0-beta-3` -- there is nothing in this release for them to receive.
- Everything above was measured on a real 340-region / 853 MB store with a real Distant Horizons client
  connected and a pre-generation running: server heap 18% of an 8 GB maximum, zero full collections, zero
  "Can't keep up" tick warnings, and a clean 22-second shutdown in the middle of the pre-generation -- the
  exact situation that previously hung for over an hour.

## [3.1.0-beta-3] - 2026-07-13

**The Overworld was showing up in the Nether. It is not any more.** If you went through a Nether portal on a
Chunksmith server, the distant terrain you saw there was not the Nether -- it was the Overworld's, pushed
into the Nether's sky. Grass, oceans and beaches, floating over the lava. Everything reported success while
it happened. This release fixes it, on both sides.

### Fixed

- **Distant terrain from one dimension no longer appears in another.** The client picked which dimension's
  LOD data to download from the FIRST dimension the server happened to list when you joined -- on any normal
  server, the Overworld -- and then never revisited that choice for the rest of the session. Walk through a
  portal and it kept downloading the Overworld's terrain and handing it to your LOD renderer, which drew it
  around you in the Nether. Neither Distant Horizons nor voxy checks that the terrain it is given belongs to
  the world you are in; they accepted it, saved it, and drew it. The client now tracks the dimension you are
  actually standing in, re-asks the server the moment you change dimension, and **refuses to inject any
  record that does not belong to the level in front of it.** Terrain from another dimension is never a
  substitute for this one's, and it is no longer treated as one.
- **The Nether's own LOD data is no longer silently skipped.** A second, independent bug, which would have
  kept the Nether empty even after the fix above. The client remembers which regions it has already drawn so
  a travel refresh does not re-push terrain you can already see -- but it remembered them by region
  coordinates ALONE. Region (0,0) is a different place in every dimension, so the moment the Overworld's
  (0,0) had been drawn, the Nether's (0,0) counted as "already done" and was dropped on the floor, for the
  whole session, without a word. Regions are now remembered per dimension, which is the only way a region
  coordinate means anything.
- **The server no longer serves an index for a dimension you are not in.** The list of regions near you is
  filtered by your renderer's range measured from YOUR position -- and a position only means something in a
  particular world. The server now answers with the dimension you are actually standing in, whatever was
  asked for, and says so in the log when the two differ. **This alone stops an already-installed
  3.1.0-beta-2 client from putting Overworld terrain in your Nether sky**, since the client files and draws
  the data under the dimension the server names.
- **Changing dimension mid-download no longer wastes the transfer.** A download in flight when you step
  through a portal is now cancelled -- on both ends -- instead of continuing to spend your connection on a
  world you have left, and the regions it had not reached are re-fetched for the dimension you are in.

### Notes

- The wire protocol is UNCHANGED (`CsLodProtocol.VERSION` is still 1). A beta-2 client and a beta-3 server
  interoperate, and the server-side correction above means such a client is partly fixed without updating --
  but only partly (it will still skip a region whose coordinates it drew in another dimension), so please
  update.
- Single-dimension play -- the overwhelmingly normal case, an Overworld-only pregen and a player who never
  leaves it -- behaves exactly as before.

## [3.1.0-beta-2] - 2026-07-13

**If you were already on the server when the pre-generation started, you now get the LOD data anyway.**
Until this build you did not, and nothing you could do in-game would fix it -- only leaving and re-joining.
Since a pre-generation takes hours, and people stay on the server while it runs, that was the normal case.

### Fixed

- **Joining before the server has any LOD data no longer costs you the whole session.** The client asked the
  server once, on join. If the store was empty -- which it always is until someone runs a pre-generation --
  the client logged one line and stood down for good: it never asked again, and the travel refresh never
  armed either, so no amount of playing or exploring brought the data in. Meanwhile the operator would start
  a pre-generation, the store would fill up over the next few hours, and every player already connected
  stayed blind to it until they thought to re-log. Now the client keeps asking (after 15s, 30s, a minute,
  then every two minutes -- a few bytes each time), **and** the server volunteers the news the moment its
  store has something to serve, so in practice the terrain simply appears. No re-log, no command, nothing to
  configure. The log says what is happening in both directions: *"the server has no pre-generated LOD data
  yet ... you do NOT need to re-log"*, and later *"the server NOW has LOD data for [...] -- fetching it"*.
- **The server no longer claims to have LOD data for a dimension it cannot serve a single region of.** A
  pre-generation creates its folder the moment it starts and only writes into it some time later, and the
  server was advertising that empty folder as data -- and issuing a download credential to go with it, which
  is how an operator could see one live token and zero files served. A dimension now counts when there is
  actually something in it.
- **A long session no longer quietly loses the fast download path.** Download credentials expire after ten
  minutes; a session lasts hours. Travelling far enough to pull in new terrain an hour after joining used to
  present an expired one, fail every fetch, and drop to the slow path for the rest of the session without
  ever saying so. The client now renews it before it can go stale.
- **The server no longer hands out a region it is still writing.** Pre-generation keeps each region file open
  and appends to it as chunks complete, so a copy taken mid-write is short: the client got part of a region
  and an error in its log. It always recovered on the next fetch, so this was invisible until now -- but now
  that players are told the moment the store comes to life, the very first region they ask for is one the
  server is in the middle of writing. A region is offered once the generator has finished with it.

### Notes

- **No protocol change.** The wire format and the protocol version are untouched. A 3.1.0-beta-2 client and
  a 3.1.0-beta-1 server work together, and so do a 3.1.0-beta-1 client and a 3.1.0-beta-2 server -- in fact
  an old client talking to a new server picks up a late pre-generation too, because the server's notice is
  simply its ordinary hello, sent again.

## [3.1.0-beta-1] - 2026-07-13

**One mod does everything now.** Chunksmith-Client -- the separate client mod that multiplayer LOD used to
require -- has been merged into Chunksmith and is discontinued. There is nothing else to install.

- **Singleplayer:** just Chunksmith. (Unchanged; this already worked.)
- **Multiplayer:** Chunksmith on the server *and* on the client. Same jar, both sides.
- **A server that only wants pre-generation:** just Chunksmith, exactly as before. Nothing new loads.

### Added

- **Multiplayer LOD is now built in.** Joining a Chunksmith server that has pre-generated LOD data, your
  client downloads it -- over the server's HTTP backchannel at network speed, or down the game connection
  if that port is not reachable -- and feeds it to whichever LOD renderer you have (Distant Horizons, or
  voxy on the cells where voxy exists). You see the whole pre-generated world at distance without ever
  having walked it. Everything the standalone client did, it still does: the store is the cache, so a
  re-join re-downloads nothing; the fetch repeats as you travel, so walking toward new terrain brings it in.

### Fixed

- **Installing Chunksmith and Chunksmith-Client together no longer crashes the game.** Both mods registered
  the same `chunksmith:lod` channel, and the second one to start died with
  `Packet type [id=chunksmith:lod] is already registered!` -- a hard crash on startup, with no explanation,
  for anyone who ran their own server and also joined someone else's. There is now exactly one registration
  of that channel, in one mod, so the collision cannot happen. If you still have the old client mod
  installed, the loader will tell you to remove it instead of crashing.

### Changed

- Chunksmith is now listed as **client-optional and server-optional**. It is genuinely both: an operator
  needs it server-side, a player joining that server needs it client-side, and a singleplayer user needs
  only the one jar.
- **Chunksmith-Client is discontinued.** Its existing builds keep working against this release -- the CSLOD
  wire format is unchanged and the protocol version is still **1**, so a 3.1 client talks to a 3.0.0-beta-4
  server and a 3.1 server serves a 1.0-beta-3 client. Nothing you have installed breaks. But there is no
  reason to keep it: remove it and Chunksmith does the job alone.

Bukkit/Paper/Folia are unaffected -- LOD is a Fabric/NeoForge/Forge feature and the plugin never carried it.

## [3.0.0-beta-4] - 2026-07-12

Security release. A full audit of the CSLOD network path found two flaws, both fixed here. Upgrading is
recommended for anyone running Chunksmith with LOD generation enabled, and especially on a server that
accepts connections from players you do not control.

### Security

- **A dimension name that arrived over the network was used to build a file path without being checked.**
  The CSLOD store turns a dimension id into a directory, and the value coming from the peer was trusted as
  written, so a malformed one could refer to a location outside the store. Every place that turns a
  dimension into a path now runs it through one shared validator that accepts only a well-formed dimension
  id and rejects everything else.
- **The CSLOD packet decoders allocated buffers from counts and lengths supplied by the sender, without
  bounding them.** A very small hostile packet could therefore ask the receiver to reserve an enormous
  amount of memory. Every count and length is now checked against a derived ceiling before anything is
  allocated, at the canonical source (`shared_common`), so the server and the client share one set of
  limits.

The wire format is **unchanged** and the CSLOD protocol version is still **1** -- a 3.0.0-beta-4 server and
an older Chunksmith-Client (or the reverse) still talk to each other.

### Added

- `CsLodBoundsTest` -- unit coverage for the new decode-time bounds.

## [3.0.0-beta-3] - 2026-07-12

Re-run a pregen and it fills in the missing LODs. Generate a world first and turn LOD on later, and the
LODs are no longer stranded -- you do not have to regenerate anything, and you do not have to reprocess
what is already done.

### Changed

- **The CSLOD store is now part of the chunk-skip decision.** When LOD generation is active, Chunksmith
  checks the store as well as the world, per chunk:
  - no chunk -> generate it (the LOD is built on the way past, as before);
  - **chunk on disk but no LOD -> load the chunk (no worldgen) and build the LOD from it**;
  - chunk *and* LOD both present -> skip entirely; no load, no write.

  Previously an already-generated chunk was skipped and never loaded, so the LOD hook never saw it: a
  world pregenerated before LOD was switched on could never get its LODs without regenerating from
  scratch or setting `forceLoadExistingChunks`. Now a plain re-run of the same selection fills the holes.
- **Only the holes are filled.** Deleting part of the CSLOD store and re-running rebuilds exactly the
  missing records and leaves the rest untouched -- the store is not rewritten wholesale.
- `forceLoadExistingChunks: true` is unchanged, and keeps its meaning as the explicit override: reprocess
  every chunk in the selection regardless, even where a LOD already exists.
- **With LOD off, nothing changes.** The skip behaviour is exactly what it has always been.

### Added

- The pregen now reports what it actually did: `generated`, `LOD-only (built from existing chunks)`, and
  `skipped (chunk + LOD present)`, plus the measured cost of the store check.
- `/cslod status` reports the store's **record count**, so it can be compared against the chunk count.

### Performance

- The presence check reads each region file's 8 KB header **once** and holds a 1024-bit bitmap, so a whole
  region's presence costs one sequential read. No records are decoded, and no file is re-opened or
  re-stat-ed per chunk.

## [3.0.0-beta-2] - 2026-07-12

LOD generation turns itself on. Install Chunksmith next to Distant Horizons or Voxy, pregenerate, and
the LODs are there -- no config file to find first.

### Changed

- **`lodEnabled` is now a TRISTATE, `auto` by default** (it was a boolean defaulting to `false`).
  - `auto` -- Chunksmith decides. LOD generation is **ON** when an LOD renderer is present in the JVM
    (Distant Horizons, Voxy, or a Voxy fork), **ON** on a dedicated server, and off otherwise. A
    dedicated server runs no renderer of its own, but its CSLOD store is exactly what Chunksmith-Client
    downloads, so the store is what it is *for*.
  - `true` / `false` -- an explicit operator decision, and it is **never** overridden. `lodEnabled: false`
    keeps LOD off even with Distant Horizons installed.
  - An existing config that already says `"lodEnabled": true` or `"lodEnabled": false` keeps working and
    keeps meaning exactly what it said. Nothing is rewritten behind you.
- **The decision is logged, once, at server start** -- which way it went and why
  (`LOD generation auto-enabled -- detected distanthorizons ...`, or `no LOD renderer detected; LOD
  generation off`). `/cslod status` reports it too. A default nobody can see is a default nobody uses.
- Renderer detection covers Distant Horizons (`distanthorizons`), Voxy (`voxy`) and the Voxy forks -- five
  of the six known forks keep the upstream `voxy` id; `neovoxy` is detected as well. A fork under an id we
  have never seen simply does not trip the auto-on, and `lodEnabled: true` still forces it.

## [3.0.0-beta-1] - 2026-07-12

The LOD feature leaves the 26.x-Fabric prototype and ships on every MC line where a player actually
has something to draw it with.

### Added

- **LOD generation.** Chunksmith can now emit level-of-detail data while it pregenerates, in its own
  neutral format (CSLOD): full block states, per-voxel biomes, and separate sky/block light carried even
  for air. ~5.8 KB per chunk, ~16% slower pregen, zero native dependencies. Off by default (`lodEnabled`).
- **Distant Horizons support -- on every LOD version, singleplayer included.** Chunksmith registers as
  DH's world-generator override and serves it straight from the CSLOD store, so DH's LODs appear for
  pregenerated area without DH generating anything (opt-in: `lodDhOverride`). `/cslod dhpush` replays an
  existing store into DH on demand -- so a world pregenerated long before DH was installed gets its LODs
  after the fact, with no regeneration. This ships on **all eight LOD cells**: Fabric 1.20.1 / 1.21.1 /
  1.21.11 / 26.x, NeoForge 1.21.1 / 1.21.11 / 26.x, and Forge 1.20.1 -- DH publishes a build for every one
  of them. We use DH's PUBLIC API only; no mixin into DH.
- **Voxy support -- where voxy actually exists.** LODs are fed to voxy live during pregen, and an existing
  store can be replayed into voxy at any time with `/cslod inject`. Ships on **Fabric 1.21.11 and Fabric
  26.x**, and only there: voxy is Fabric-only and upstream has never published a 1.20.1 or a 1.21.1 build
  on any loader. On every other cell the voxy seam is simply not compiled in -- Chunksmith never claims a
  renderer it cannot feed.
- **Singleplayer gets LODs with no client mod at all.** In singleplayer the integrated server runs inside
  the client JVM, so Chunksmith hands the player's own DH (and voxy, where it exists) their data
  DIRECTLY -- no Chunksmith-Client, no network. That used to work only on Fabric 26.x; it now works on
  every version and loader where a renderer exists.
- **The LOD server ports to the versions people actually play.** The whole server side -- the CSLOD store,
  the HTTP backchannel (game port + 1, zero config), the authenticated in-band handshake and tokens, the
  in-band fallback transfer, the worldgen hook, and `/cslod` -- now ships on **Fabric 1.20.1, 1.21.1,
  1.21.11 and 26.x**, **NeoForge 1.21.1, 1.21.11 and 26.x**, and **Forge 1.20.1** -- the versions people
  actually run. The remaining versions (1.20.4, 1.20.6, 1.21.4, 1.21.5, 1.21.8, 1.21.10) can carry the
  feature and will get it -- they are simply not in this release.
- The wire protocol is IDENTICAL on every one of them. The format is the disk format is the wire format,
  and it lives in one place -- so any Chunksmith-Client talks to any Chunksmith server.
- `/cslod status` and `/cslod token <player>` on every LOD cell; `/cslod dhpush` on every LOD cell;
  `/cslod inject` on the two cells where voxy exists. `/cslod status` reports only the renderers a cell can
  actually feed -- it says "voxy: no build for this loader/MC" rather than pretending voxy is merely absent.

### Changed

- **Declared conflicts.** Chunksmith's LOD cells now hard-conflict with the other mods that stream LOD
  data into a client's renderer -- `lss` (LOD Server Support), `voxyserver` (Voxy Server), and `lodserver`
  (our own predecessor). Two uncoordinated writers into one LOD database means duplicated downloads and a
  real risk of racing voxy's database-local id allocation. Distant Horizons is deliberately NOT a
  conflict: it is a renderer we feed. (Forge 1.20.1's `mods.toml` has no incompatible-dependency type, so
  there the clash is reported loudly in the log instead.)

### Fixed

- **`pack.mcmeta` on 1.21.10 and 1.21.11.** From MC 1.21.9 the pack-metadata codec validates a mod jar
  TWICE -- once as a resource pack, once as a data pack -- and each pack type has its own threshold above
  which `min_format`/`max_format` become mandatory (64 for resources, 81 for data). The resource format on
  those versions (69 and 75) lands between the two, so no single `pack.mcmeta` can satisfy both, and the
  client logged `Couldn't load chunksmith pack metadata ... missing mandatory fields min_format and
  max_format`. The Fabric and NeoForge cells now ship no `pack.mcmeta` at all (both loaders synthesise
  correct per-type metadata when a mod jar omits it); the Forge cells, where the file is mandatory, use the
  exact-range form with the data-pack format, the only value above both thresholds.
- Build scripts silently ignored all but the first target (`build-fabric.ps1 1.21.8 26.1` built only
  1.21.8), aborted the whole matrix on the first failing cell, and could corrupt each other when run
  concurrently (all cells share `shared_common`). All three fixed; a build lock now prevents overlap.
- **In-band LOD requests no longer trust the client's numbers.** A region count arriving off the wire was
  used to pre-size a list and to slurp every requested region file into memory on the server thread: a
  large but perfectly legitimate request meant hundreds of megabytes and a multi-second stall, and a
  hostile one was a single packet away from an out-of-memory kill. Requests are now bounded, and the
  transfer streams each region a slice at a time off disk instead of buffering it. The client's declared
  LOD radius is clamped as well.
- The NeoForge 26.x manifest declared its Minecraft and NeoForge version requirements under the mod id
  `chunky` (a leftover from the fork's ancestry), so the loader never applied them.
- LOD store logging went to `System.out` instead of the mod's logger.


## [2.2.3] - 2026-07-10

Fixes a hard crash at startup that could hit large modpacks. Chunksmith's five optional worldgen/entity diagnostic mixins are now best-effort (`require = 0`), so another mod that removes or overrides one of their target methods can no longer take the game down at boot. Reported on NeoForge 1.21.1 in a ~400-mod pack.

Chunksmith's core behavior is unchanged - the functional mixins (keep-awake, chunk housekeeping, entity-retention, client housekeeping) stay hard-required, so a genuine problem there still fails loudly.

## 2.2.1 (2026-07-05) -- metadata + build hygiene bugfix

- Issue-tracker URL: every mod manifest now points to the mod_support hub
  (github.com/Kishku7/mod_support/issues), single-sourced + audit-checked via
  scripts/_metadata.py (previously the mod's own disabled Issues tab).
- pack.mcmeta: the 1.21.10 / 1.21.11 cells (Fabric/Forge/NeoForge) now ship the
  supported_formats range form required for pack_format > 64 (were plain int,
  which caused the client to skip the mod resource pack on 1.21.9+).
- Removed the dead oss.sonatype.org snapshots repo and the unused Architectury
  maven repo from all cell build scripts.
- Internal docs folder renamed .docs -> docs (gitignored). README build-script
  references corrected to scripts/build-*.ps1.

## 2.1.3

### Minecraft 26.3-snapshot-2 support

Adds Fabric support for 26.3-snapshot-2. This build covers BOTH 26.3-snapshot-1 and 26.3-snapshot-2,
so snapshot-1 users should upgrade. It is a pure dependency bump: no worldgen or mixin logic changed
(verified against the 26.3-snapshot-1 -> snapshot-2 decompiled source diff -- the snapshot-2 worldgen
refactor does not touch any Chunksmith injection point).

### Packaging + build hygiene

- Every jar now ships a correct per-version `pack.mcmeta` (added the missing Fabric resource metadata;
  corrected the NeoForge `pack_format`).
- The mod and plugin now compile clean under `-Xlint:all` with zero warnings. These are behavior-
  preserving changes only (final classes, explicit numeric casts, a non-deprecated permission API call,
  and justified suppressions for intentional cross-version Bukkit/Paper API use).

## 2.1.2

### Fixed: worldgen entities (mobs, item frames, armor stands, etc.) could fail to save

During large pre-generation runs, entities that spawn as brand-new chunks are generated could,
in some cases, fail to be saved -- in practice, "mobs that just don't persist."

**What was happening.** When Minecraft unloads a freshly generated chunk's entities, it normally
first reads any already-saved entities for that chunk so they can be merged before the new data is
written. Chunksmith skips that read when a chunk has no saved entity data -- a real optimization that
keeps memory and disk under control during big pre-gens. The problem was *how* it decided "is there
saved entity data?": that check ran off the storage thread, against a cache that was never
refreshed, and it ignored writes that were still queued in memory but not yet flushed to disk. So a
chunk that was stored, unloaded, and then re-loaded before its write reached disk could have its
just-saved entities overwritten -- and lost.

**The fix.** The "is there saved entity data?" check now runs on the chunk-storage system's own
thread -- the single thread that owns both the in-memory write queue and the region files -- and it
checks the queued writes AND the on-disk data. It can no longer race the writer, read a half-written
file header, or trust a stale cache. If any saved data exists, or anything is uncertain, Chunksmith
performs the full, safe read-and-merge exactly as vanilla would. Nothing is ever skipped when data
might exist, so no entity can be lost.

This fix is applied across every supported Minecraft version and loader.

### Also in this release

- **Unified version.** All Minecraft lines (1.20.x, 1.21.x, 26.x) and all loaders (Fabric, Forge,
  NeoForge, and the Bukkit/Paper plugin) are now on a single version, 2.1.2, so every supported
  version carries the same set of fixes.
- **26.x loader metadata.** Minecraft version ranges are now closed (a 26.1 build targets 26.1.x
  only, a 26.2 build targets 26.2.x only, and so on) instead of open-ended, so a build can no longer
  claim to support a Minecraft line it was not built and tested against.

---

Earlier releases were published on Modrinth only; this is the first in-repo changelog entry.

