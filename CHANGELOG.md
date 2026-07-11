# Chunksmith Changelog

## 2.2.3 (2026-07-10) -- crash-hardening + build unification

- Crash fix: the five worldgen/entity DIAGNOSTIC mixin injectors are now
  require=0 (best-effort). A conflicting mod that removes or claims one of their
  target calls can no longer hard-crash the game at bootstrap (reported on
  NeoForge 1.21.1 in a ~400-mod pack). Functional mixins (keep-awake, chunk
  housekeeping, entity-retention, client housekeeping) stay hard-required, so a
  genuine miss there still fails loudly.
- Build: all 26-line loader entrypoints are single-sourced through the cog
  generator and the 26 cells now build from cog-gen like the rest of the matrix.
  No functional change to the produced jars.
- Docs: README platform line corrected to include Forge; removed the Modrinth
  download link per the two-links README convention.

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
