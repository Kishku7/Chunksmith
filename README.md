# Chunksmith (Minecraft 26.x line)

Chunksmith is a chunk pre-generator for Minecraft, maintained as a fork of pop4959's
Chunky. This branch (`26`) covers the entire Minecraft 26 line -- 26.1 through 26.3 -- as
one unified codebase: a shared core, a shared mod layer, and per-loader builds that ship
the whole range.

Version: **2.1.3**

## What it does

Pre-generates world chunks ahead of time so they are ready before players arrive,
eliminating the lag of on-demand generation. Core features (shared by every platform):

- Generation by shape (square, circle, diamond, triangle, star, and more) centered on
  coordinates, world spawn, or the world border, by radius/diameter.
- Multi-world, with live progress, rate, ETA, and an optional boss bar.
- Pause / continue / cancel, and continue-on-restart.
- World trimming (delete chunks outside a selected region).
- Configurable work-per-tick with an adaptive throttle that backs off when tick health
  degrades.
- A developer API (generation progress/complete events).

Commands run under `/chunksmith` (alias `/cs`); permission nodes are `chunksmith.command.*`.

## Improvements over upstream Chunky

This is not a straight re-skin -- the 26 line is a substantial cleanup of the fork:

- **One branch for the whole 26.x line.** What used to be three separate branches
  (26.1 / 26.2 / 26.3) is now a single unified tree: one edit ships every version, and
  backporting an idea across the line is trivial.
- **Finished rebrand, Chunky -> Chunksmith.** Internal package `org.popcraft.chunky` ->
  `com.kishku7.chunksmith`, every class name, permission nodes (`chunksmith.command.*`,
  with legacy `chunky.command.*` still honored), JVM `-D` flags, the Mixin package prefix,
  and the language strings -- while preserving upstream credit lines and the `ChunkyBorder`
  integration names.
- **Inherited dead code removed.** Dropped the cross-platform disk-space estimation
  (unreliable/blocked on some platforms), unused Hilbert-curve imports, and pre-26
  (1.20.x / 1.21.x) code paths no longer relevant to the 26 line.
- **Cross-loader consolidation.** Duplicated Fabric/NeoForge Minecraft code merged into a
  single `shared_minecraft` layer -- one copy, both loaders.
- **Plugin unified onto the mod's core.** The Bukkit/Paper/Folia plugin now builds on the
  same `shared_common` as the mod (no divergent copy); the Paper and Folia helpers were
  merged and duplicate helpers folded together.
- **Pruned stray pre-26 builds** from the 26 line (the old Forge 1.20/1.21 folders).

## Layout

| Directory | What it is |
|-----------|------------|
| `shared_common/` | MC-agnostic core -- commands, tasks, shapes, trim, config, the API, region NBT. Shared by the mod AND the plugin. |
| `shared_minecraft/` | Shared mod layer -- the Mixins/accessors that keep big pre-gens safe on an unpatched (vanilla) server. Used by the Fabric and NeoForge builds only. |
| `Fabric/` | Fabric mod build -- one source, three jars (26.1, 26.2, 26.3). |
| `NeoForge/` | NeoForge mod build -- 26.1 and 26.2 (26.3 pending; no NeoForge 26.3 yet). |
| `Plugin/` | Bukkit/Paper/Folia plugin -- one jar, runs Spigot through Folia. |

Each directory has its own README with the detail for that piece.

## Platform / version coverage

| Platform | MC versions | Builds |
|----------|-------------|--------|
| Fabric (mod) | 26.1, 26.2, 26.3-snapshot-1 | 3 jars |
| NeoForge (mod) | 26.1, 26.2 | 2 jars |
| Plugin (Bukkit/Paper/Folia) | 26.1 -- 26.3-snapshot-1 | 1 jar |

The compiled mod code is identical across the versions within each loader; only the
dependency versions and the declared compatibility range differ per target.

## Mod vs plugin: same core, different depth

The shared core (`shared_common`) is identical everywhere. The difference is the
server-internal work in `shared_minecraft`: the mod Mixins into Minecraft to keep huge
pre-gens safe (keep-awake during generation, prompt chunk unloading, and a worldgen
entity-retention fix) on an otherwise-unpatched server. A Bukkit plugin cannot Mixin --
but on Paper/Folia it does not need to: the server's Moonrise chunk system already
provides those protections (verified against decompiled Paper 26.1.2/26.2 source). So the
plugin reaches the same outcomes through the public API or the server itself, and is
thinner by design rather than less capable. We do not re-implement, or claim, fixes the
platform already provides. See `Plugin/README.md` and `.docs/plugin-api-parity.md`.

## Build

    pwsh build-all-fabric.ps1       # Fabric, all 26.x targets -> dist/
    pwsh build-all-neoforge.ps1     # NeoForge 26.1 + 26.2 -> dist/
    cd Plugin && ./gradlew build    # plugin -> Plugin/bukkit/build/libs/

## Credits

Original Chunky by pop4959. The Paper/Folia chunk-system internals referenced above are
Moonrise (Spottedleaf). Chunksmith is maintained by Kishku7.
