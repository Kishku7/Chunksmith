# Chunksmith - `mod/1.20.x` branch

[![Discord](https://img.shields.io/badge/Discord-Join-5865F2?logo=discord&logoColor=white)](https://discord.gg/2ZxzbCzAHe)

This is the **Minecraft 1.20 backport** branch of Chunksmith (the mod, for **Fabric** and **NeoForge**). It carries the same feature set as the mainline mod, retargeted to the 1.20 line and split into build variants where a Minecraft API break can't be spanned by a single jar.

Chunksmith is a chunk pre-generator that **paces generation to keep your server healthy** - it builds large areas ahead of time without saturating your disk or freezing the server. It is a fork of [Chunky](https://github.com/pop4959/Chunky) by **pop4959**, licensed under **GPL-3.0**, maintained by **Kishku7**.

> The Paper / Spigot / Folia **plugin** for 1.20 lives on the separate `plugin/1.20.x` branch, not here. This branch is mod-only.

## Variant - Minecraft version map

The 1.20 line is covered by **three variant pairs** (one Fabric jar + one NeoForge jar each). The split exists because of one Minecraft-internal API change Chunksmith hooks, plus the Java 17 -> 21 floor:

| Variant module | Minecraft | Java (JDK) | Loaders | Why this is its own jar |
|---|---|---|---|---|
| `fabric-1.20.1` / `neoforge-1.20.1` | **1.20.1** only | 17 | Fabric (+Quilt), NeoForge (also Forge 47.x) | `ServerChunkCache.getChunkFutureMainThread` returns `Either<...>` here |
| `fabric-1.20.4` / `neoforge-1.20.4` | **1.20.2 - 1.20.4** | 17 | Fabric (+Quilt), NeoForge | For Chunksmith's targets this band is byte-identical to 1.20.1 (still `Either`, `ChunkStatus` in `world.level.chunk`) - separate only for the loader/API metadata (NeoForge namespace + Fabric API) |
| `fabric-1.20.6` / `neoforge-1.20.6` | **1.20.5 - 1.20.6** | 21 | Fabric (+Quilt), NeoForge | `getChunkFutureMainThread` returns `ChunkResult<...>`, `ChunkStatus` moved to `world.level.chunk.status`, and Java floor rises to 21 - all at 1.20.5 |

**The only Chunksmith-target API break across the whole 1.20 line is at 1.20.5**, where `getChunkFutureMainThread` changed `Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>` -> `ChunkResult<ChunkAccess>` and `ChunkStatus` moved package. Everything else Chunksmith hooks (`ChunkStorage.worker` / `IOWorker.pendingWrites`, `WorldGenRegion` fields, `HangingEntity`, `StructureStart.placeInChunk`, the `MinecraftServer`/`ServerLevel`/`ChunkMap` targets) is byte-stable from 1.20.1 to 1.20.6. (Verified against the official Mojang mappings: `ChunkResult` does not exist before 1.20.5; `ChunkHolder.ChunkLoadingFailure` exists through 1.20.4. The 1.20.1-only and 1.20.2-1.20.4 jars therefore share one mixin shape.)

> A jar for one band will **not** load on another - the `getChunkFutureMainThread` invoker descriptor differs and the metadata version ranges are exclusive. Install the jar that matches your Minecraft version.

NeoForge namespace by Minecraft version:
- **1.20.1** = the original NeoForge release, published as the Forge-coordinate fork `net.neoforged:forge:1.20.1-47.1.x`, still in the `net.minecraftforge.*` API namespace (binary-compatible with Forge 47.x, so the jar also runs on Forge 1.20.1).
- **1.20.2+** = NeoForge's own `net.neoforged:neoforge:20.x` coordinate in the `net.neoforged.*` API namespace, mojmap-native at runtime.

## Download & install

- **Fabric / Quilt:** drop `Chunksmith-Fabric-<mcversion>-<version>.jar` into `mods/` (requires Fabric API). Quilt runs the Fabric jar as-is.
- **NeoForge:** drop `Chunksmith-NeoForge-<mcversion>-<version>.jar` into `mods/`. On 1.20.1 the same jar also works on Forge 47.x.

Pick the jar whose Minecraft version matches your server (see the map above). Remove any existing **Chunky** jar - Chunksmith supersedes it (on Fabric/NeoForge it will warn and ask you to remove Chunky if both are present).

## Features (this branch)

Same delta as mainline Chunksmith, adapted to 1.20:

1. **Rebrand / drop-in migration** - command is **`/cs`** (alias **`/chunksmith`**); the legacy **`/chunky`** / **`/cy`** still work but print a deprecation notice. On first run Chunksmith adopts an existing Chunky config in place (`config/chunky` -> `config/chunksmith`). Conflict-safe if Chunky is also installed.
2. **Adaptive I/O throttle** - generation concurrency is steered by live server tick-health (an mspt EWMA + AIMD controller) with a per-chunk latency backstop, so a heavy pre-gen yields to players automatically and never saturates a slow disk.
3. **Write-queue backpressure** - watches the deferred region-write backlog (`IOWorker` queue) and holds off dispatch when it exceeds a cap, resuming once it drains, so `pause`/`stop` stay instant and the unflushed-write window stays bounded.
4. **Worldgen-overreach diagnostic** - collapses the vanilla "tried to setBlock outside its chunk" log spam into a single structured report (offending feature, chunks, Y range, block count).
5. **Structure-fault diagnostic** - reports structure / block-attached-entity placement faults.
6. **SLF4J reporter logging** - all reports reach `latest.log` on every platform.

### 1.20-specific notes

- **Feature 2 "keep-awake" half is N/A on the entire 1.20 line.** 1.20.1 - 1.20.6 `MinecraftServer` has no `emptyTicks` field and no field-based empty-server pause to fight (that arrived later), so there is nothing to keep awake. The mspt-driven throttle (the primary signal) is fully present and active.
- **Feature 5 is "far-anchor only" on the entire 1.20 line.** The 1.20 predecessor of the block-attached-entity class is `HangingEntity`, whose `readAdditionalSaveData` only logs the far-anchor (>16 blocks) case - there is no null/legacy missing-anchor branch on 1.20 (that fault sub-type only appears in later Minecraft). So the diagnostic reports the far-anchor fault but has no missing-anchor sub-case here.

All other features (1, 3, 4, 6, and the mspt throttle of 2) are fully present on every 1.20 variant, both loaders.

## Building from source

Requirements: **JDK 17 and JDK 21 both installed** (different variants need different Java versions - see the map). The Gradle wrapper is included.

Set `JAVA_HOME` to a **JDK 21** install before invoking Gradle. Gradle's *configuration* of the 1.20.5+ modules requires Java 21, while each module's own toolchain pins the correct compile JDK (17 for the 1.20.1 / 1.20.4 variants, 21 for the 1.20.6 variants) automatically. (Do not rely on the machine-global `JAVA_HOME` if it points at a newer JDK - Vineflower/decompile steps can fail under JDK 25+.)

Build everything:

```bash
# JAVA_HOME must be a JDK 21 install
./gradlew build
```

Or build a single variant:

```bash
./gradlew :chunksmith-fabric-1.20.1:build       # Fabric, MC 1.20.1   (JDK17)
./gradlew :chunksmith-fabric-1.20.4:build       # Fabric, MC 1.20.2-1.20.4 (JDK17)
./gradlew :chunksmith-fabric-1.20.6:build       # Fabric, MC 1.20.5-1.20.6 (JDK21)
./gradlew :chunksmith-neoforge-1.20.1:build     # NeoForge, MC 1.20.1   (JDK17)
./gradlew :chunksmith-neoforge-1.20.4:build     # NeoForge, MC 1.20.2-1.20.4 (JDK17)
./gradlew :chunksmith-neoforge-1.20.6:build     # NeoForge, MC 1.20.5-1.20.6 (JDK21)
```

Output jars land in each module's `build/libs/` as `Chunksmith-{Fabric,NeoForge}-<mcversion>-<version>.jar`.

### Toolchain per variant

| Variant | Build toolchain | Mappings |
|---|---|---|
| `fabric-1.20.1` / `fabric-1.20.4` / `fabric-1.20.6` | fabric-loom | official Mojang mappings (mojmap) |
| `neoforge-1.20.1` | ForgeGradle 6 + SpongePowered Mixin gradle plugin (NeoForge is the Forge-namespace fork at 1.20.1) | official Mojang mappings |
| `neoforge-1.20.4` | NeoGradle 7 (no mixin annotation processor - see below) | official Mojang mappings |
| `neoforge-1.20.6` | ModDevGradle | official Mojang mappings (mojmap-native, empty refmap) |

The three loader toolchains coexist in one multi-module build sharing a single `common/` + `nbt/` (both pure-Java, Minecraft-free). NeoForge 20.2+ is mojmap-native at runtime, so its mixin **refmap is empty** (dev names already equal runtime names); the `neoforge-1.20.4` module builds without the SpongePowered mixin annotation processor because that processor's bundled obfuscation service insists on searge mappings that do not exist for a mojmap-native build - the FML mixin loader resolves the mojmap targets directly at runtime.

## Credits & license

Original **Chunky** by pop4959 and contributors. **Chunksmith** fork maintained by Kishku7.
Licensed under the **GNU General Public License v3.0** - see [`LICENSE`](LICENSE).
