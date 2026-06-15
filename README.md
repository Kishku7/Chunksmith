# Chunksmith - `mod/1.21.x`

[![Discord](https://img.shields.io/badge/Discord-Join-5865F2?logo=discord&logoColor=white)](https://discord.gg/2ZxzbCzAHe)

Chunksmith is a Minecraft chunk pre-generator that **paces generation to keep your server healthy** - it builds large areas ahead of time without saturating your disk or freezing the server. It is a fork of [Chunky](https://github.com/pop4959/Chunky) by **pop4959**, licensed under **GPL-3.0**, and maintained by **Kishku7**.

This branch is the **Minecraft 1.21 line** backport (the mod only - Fabric + NeoForge). Quilt runs the Fabric jar. The Paper/Spigot/Folia plugin lives on `plugin/1.21.x`. For the landing page and the current (26.x) release, see the [`main`](https://github.com/Kishku7/Chunksmith) branch.

## Supported Minecraft versions

The 1.21 line has several hard API breaks for Chunksmith's specific targets, so it ships as **build variants**, one per sub-range. Each variant is a separate jar (per loader); pick the one matching your server's Minecraft version.

| Variant | Minecraft | Java | Notes |
|---------|-----------|------|-------|
| `1.21.1`  | 1.21 - 1.21.1   | 21 | Anchor. Keep-awake N/A (no empty-server pause yet). |
| `1.21.4`  | 1.21.2 - 1.21.4 | 21 | Keep-awake restored (empty-server pause arrives 1.21.2). |
| `1.21.8`  | 1.21.5 - 1.21.8 | 21 | 1.21.5 NBT + ticket-system refactor. |
| `1.21.10` | 1.21.9 - 1.21.10| 21 | 1.21.9 ticket/spawn refactor; recompile boundary. |
| `1.21.11` | 1.21.11         | 21 | 1.21.11 `Identifier` rename, permission overhaul, `SimpleRegionStorage`. |

Quilt rides the matching Fabric jar. NeoForge versions track the loader's MC build (21.1 / 21.4 / 21.8 / 21.10 / 21.11).

## Build

All variants build on **JDK 21**. Set `JAVA_HOME` to a JDK 21 before invoking Gradle (the toolchain pins the language level, but the Gradle/decompile step expects 21):

```
# one variant pair
./gradlew :chunksmith-fabric-1.21.8:build :chunksmith-neoforge-1.21.8:build

# everything
./gradlew build
```

Output jars land in each variant's `build/libs/` as `Chunksmith-Fabric-<ver>-*.jar` / `Chunksmith-NeoForge-<ver>-*.jar`. The shared, Minecraft-free `common` and `nbt` modules are shaded into every variant jar.

### Toolchain

| Layer | Tool |
|-------|------|
| Gradle | 8.14 (wrapper) |
| Fabric (1.21.1 - 1.21.10) | fabric-loom 1.12.7, official Mojang mappings |
| Fabric (1.21.11) | fabric-loom 1.13.6 (required by fabric-api 0.141.4+1.21.11), official Mojang mappings |
| NeoForge (all) | ModDevGradle 2.0.141, `net.neoforged:neoforge:21.x`, mojmap-native |

**NeoForge mixins** are built with **no annotation processor and no refmap**: NeoForge 20.2+ is mojmap-native at runtime, so the FML mixin loader resolves `@Inject`/`@Redirect`/`@Accessor` targets directly by their Mojang name. The same mixin classes are compile-validated on the Fabric variants (loom refmap), so runtime resolution by name is sound.

## Features (per variant)

| # | Feature | Status across the 1.21 line |
|---|---------|------------------------------|
| F1 | Rebrand (`/cs` + `/chunksmith`; `/chunky` + `/cy` deprecated; config auto-migration) | present on all |
| F2 | Adaptive I/O throttle - mspt EWMA tick-health signal | present on all |
| F2 | Keep-awake (reset the empty-server idle counter while generating) | **N/A on 1.21.1**; present 1.21.2+ |
| F3 | Write-queue backpressure (`IOWorker` pending-write depth gate) | present on all |
| F4 | Worldgen-overreach diagnostic (`WorldGenRegion` far-write capture) | present on all |
| F5 | Structure-fault diagnostic (`BlockAttachedEntity` invalid-position capture) | present on all (far-anchor case only - the missing-anchor sub-case does not exist for this class) |
| F6 | SLF4J reporter logging | present on all |

## Per-range API notes (Chunksmith's targets only)

- **1.21.2** - `MinecraftServer.emptyTicks` / `tickConnection()` appear (empty-server pause); keep-awake becomes applicable. `IOWorker.pendingWrites` changes `Map` -> `SequencedMap`. `RegistryAccess.registryOrThrow` -> `lookupOrThrow`; `Level.getMinBuildHeight` -> `getMinY`; `ServerPlayer.teleportTo` gains the `Set<Relative>` overload.
- **1.21.5** - `CompoundTag.getString` returns `Optional<String>` (chunk-NBT status path). Ticket system overhaul: `TicketType` becomes a record (`new TicketType(...)`), `addRegionTicket`/`removeRegionTicket` -> `addTicketWithRadius`/`removeTicketWithRadius`.
- **1.21.6** - `ServerPlayer.serverLevel()` removed; use `(ServerLevel) player.level()` (covariant from 1.21.6). The `BlockAttachedEntity` ValueIO refactor does not affect the F5 redirect (the inner `Logger.error(String,Object)` call is unchanged).
- **1.21.9** - `TicketType` constructor changes to `(long timeout, int flags)` with `FLAG_LOADING` / `FLAG_SIMULATION` bit constants. `Level.getSharedSpawnPos/Angle` removed -> `getRespawnData().pos()/yaw()/pitch()`. Recompile boundary (separate jar).
- **1.21.11** - `ChunkStorage` removed; `ChunkMap` now extends `SimpleRegionStorage` (F3 accessor retargeted). `net.minecraft.resources.ResourceLocation` renamed to `Identifier`; `ResourceKey.location()` -> `identifier()`. Permission overhaul: `hasPermissions(int)` -> `permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)`.

Worldgen (`WorldGenRegion.generatingStep` / `ChunkStep`), structure placement (`StructureStart.placeInChunk`), the chunk-future path (`ServerChunkCache.getChunkFutureMainThread`, ChunkResult era), and `ServerLevel.entityManager` are byte-stable across the whole 1.21 line.

## Usage

```
/cs start          # pre-generate the current/selected area
/cs pause          # pause (resumable)
/cs continue       # resume a paused task
/cs progress       # show rate / ETA / throttle status
```

`/chunksmith` is a full-name alias; legacy `/chunky` and `/cy` still work with a deprecation notice. Requires the Fabric API on Fabric. Remove any existing Chunky jar.

## Credits & license

Original **Chunky** by pop4959 and contributors. **Chunksmith** fork maintained by Kishku7. Licensed under the **GNU General Public License v3.0** - see [`LICENSE`](LICENSE).
