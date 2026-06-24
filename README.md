# Chunksmith

Chunksmith is a Minecraft chunk pre-generator that generates chunks quickly,
efficiently, and **safely**. On top of fast pre-generation it adds an adaptive
I/O throttle (keeps generating 24/7 even with players online), region
write-backpressure protection, and worldgen diagnostics (overreach detection +
structure-fault attribution).

Ships as a **Fabric + NeoForge mod** and a **Paper / Spigot plugin**.
Originally derived from Chunky by pop4959; now developed independently. Licensed GPL-3.0.

**Current release:** 2.1.0 (the unified Minecraft 26.x line)

**Download:** https://modrinth.com/mod/chunksmith

## Branches

`main` is this landing page. Code lives on per-line branches.

**Active**
- [`26`](https://github.com/Kishku7/Chunksmith/tree/26) - the unified Minecraft 26 line (26.1, 26.2, 26.3-snapshot-1). One source tree builds the whole line and contains both the mod (Fabric + NeoForge) and the Bukkit/Paper/Spigot plugin.

**Frozen (legacy lines)**
- [`1.20.x`](https://github.com/Kishku7/Chunksmith/tree/1.20.x) - Minecraft 1.20.1-1.20.6 (mod + plugin)
- [`1.21.x`](https://github.com/Kishku7/Chunksmith/tree/1.21.x) - Minecraft 1.21-1.21.11 (mod + plugin)

Within the `26` branch the layout is `shared_common/` (MC-agnostic core) + `shared_minecraft/` (shared mod mixins) + `Fabric/`, `NeoForge/`, and `Plugin/`. The frozen lines use the older loader-on-top layout (`Fabric/<version>`, `NeoForge/<version>`, with shared `common/` + `nbt/`, plus a `Plugin/` sub-build for the Bukkit/Paper plugin).

## Usage

Primary command is `/cs` (alias `/chunksmith`); `/chunky` and `/cy` are deprecated
aliases that redirect to `/cs`. On servers the mod/plugin is op-gated; on Bukkit it
uses the `chunksmith.command.*` permission namespace (legacy `chunky.command.*` still works).

Common workflow:

- `/cs world <world>` — choose the target world (defaults to the current overworld)
- `/cs spawn` or `/cs center <x> <z>` — set the generation center
- `/cs radius <blocks>` — set the radius (or `/cs worldborder` to use the world border)
- `/cs start` — begin generating
- `/cs pause` / `/cs continue` — pause and resume (progress is saved)
- `/cs cancel` then `/cs confirm` — stop and discard the current/saved task

Generation is throttled automatically against server tick-health, so it can run
while players are online without tanking TPS.

## License

GPL-3.0-only. Original Chunky © pop4959. Chunksmith modifications © Kishku7.