# Chunksmith Changelog

## [Unreleased]

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
