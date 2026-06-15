# Chunksmith - `mod/26.2.x` branch (PRE-RELEASE)

[![Discord](https://img.shields.io/badge/Discord-Join-5865F2?logo=discord&logoColor=white)](https://discord.gg/2ZxzbCzAHe)

This is the **Minecraft 26.2 pre-release** branch of Chunksmith (the mod). Minecraft 26.2 is still in **release-candidate** stage, so this branch tracks it as code only.

> **Pre-release policy:** this branch receives code pushes for the 26.2 line, but **no GitHub Release is published until Minecraft 26.2 is stable** (Modrinth gets beta versions; the latest GitHub release stays the last stable Minecraft line). See the project's publishing rules.

Chunksmith is a chunk pre-generator that **paces generation to keep your server healthy**. It is a fork of [Chunky](https://github.com/pop4959/Chunky) by **pop4959**, licensed under **GPL-3.0**, maintained by **Kishku7**.

> The Paper / Spigot / Folia **plugin** for 26.2 lives on the separate `plugin/26.2.x` branch, not here. This branch is mod-only.

## Variant - Minecraft version map

| Variant module | Minecraft | Java (JDK) | Loaders | Status |
|---|---|---|---|---|
| `fabric-26.2` | **26.2** (built against 26.2-rc-2) | 25 | Fabric (+Quilt) | **builds** |
| `neoforge-26.2` | NeoForge 26.2 | 25 | NeoForge | **source present, not yet buildable** (see below) |

### NeoForge 26.2 status

The NeoForge variant source is present in `neoforge-26.2/` (retargeted to NeoForge 26.2), but it is **not wired into the Gradle build yet** and is **pending a buildable NeoForge 26.2 toolchain**:

- There is **no public NeoForge 26.2 release**. Building it requires a locally-built NeoForge 26.2 alpha installed in the local Maven repository.
- The repo's Fabric/NeoForge build plugin (`neo-loom` `1.16.0-alpha.4`) **cannot consume that local `userdev` artifact**: its `afterEvaluate` Minecraft-setup step mutates every declared repository's content descriptor *after* the local repository has already been used to resolve the NeoForge dependency, throwing `Cannot mutate content repository descriptor ... after repository has been used`. This is a limitation of the neo-loom alpha, not of the Chunksmith code.

The module is therefore intentionally excluded from `settings.gradle.kts` so the build stays green; it is **not** a broken module in the build. Re-include it (commented instructions are in `settings.gradle.kts`) once a public NeoForge 26.2 release exists or neo-loom can consume the local userdev. The NeoForge code itself is the same mainline Chunksmith mod code as the (building) `mod/26.1.x` NeoForge variant, retargeted to 26.2.

## Download & install

- **Fabric / Quilt:** drop `Chunksmith-Fabric-<version>.jar` into `mods/` (requires Fabric API). Quilt runs the Fabric jar as-is.

Remove any existing **Chunky** jar - Chunksmith supersedes it.

## Features (this branch)

The full mainline Chunksmith feature delta (rebrand `/cs`, adaptive I/O throttle incl. keep-awake, write-queue backpressure via `SimpleRegionStorage`/`IOWorker`, worldgen-overreach diagnostic, structure-fault diagnostic, SLF4J reporter logging). Same set as `mod/26.1.x`.

## Building from source

Requirements: **JDK 25**. Set `JAVA_HOME` to a JDK 25 install.

```bash
# JAVA_HOME must be a JDK 25 install
./gradlew :chunksmith-fabric-26.2:build       # Fabric, MC 26.2
```

Output jar: `fabric-26.2/build/libs/Chunksmith-Fabric-<version>.jar`.

### Toolchain

| Variant | Build toolchain | Mappings |
|---|---|---|
| `fabric-26.2` | neo-loom (`org.relativitymc.neo-loom`) | official Mojang mappings (mojmap-native) |

## Credits & license

Original **Chunky** by pop4959 and contributors. **Chunksmith** fork maintained by Kishku7.
Licensed under the **GNU General Public License v3.0** - see [`LICENSE`](LICENSE).
