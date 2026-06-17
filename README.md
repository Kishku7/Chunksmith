# Chunksmith

[![Discord](https://img.shields.io/badge/Discord-join-5865F2?logo=discord&logoColor=white)](https://discord.gg/2ZxzbCzAHe)

Chunksmith is a Minecraft chunk pre-generator that generates chunks quickly,
efficiently, and **safely**. On top of fast pre-generation it adds an adaptive
I/O throttle (keeps generating 24/7 even with players online), region
write-backpressure protection, and worldgen diagnostics (overreach detection +
structure-fault attribution).

Ships as a **Fabric + NeoForge + Forge mod** and a **Paper / Spigot / Folia plugin**.
Originally derived from Chunky by pop4959; now developed independently. Licensed GPL-3.0.

**Download:** https://modrinth.com/mod/chunksmith

## Branches

`main` is this landing page. Code lives on per-target branches — one per Minecraft
version line, split into the mod build and the plugin build.

### Mod (Fabric / NeoForge / Forge)
- [`1.20.x`](https://github.com/Kishku7/Chunksmith/tree/1.20.x) — Minecraft 1.20.1–1.20.6
- [`1.21.x`](https://github.com/Kishku7/Chunksmith/tree/1.21.x) — Minecraft 1.21–1.21.11
- [`26.1`](https://github.com/Kishku7/Chunksmith/tree/26.1) — Minecraft 26.1.x
- [`26.2`](https://github.com/Kishku7/Chunksmith/tree/26.2) — Minecraft 26.2

### Plugin (Paper / Spigot / Folia)
- [`plugin/1.20.x`](https://github.com/Kishku7/Chunksmith/tree/plugin/1.20.x) — Minecraft 1.20.x
- [`plugin/1.21.x`](https://github.com/Kishku7/Chunksmith/tree/plugin/1.21.x) — Minecraft 1.21.x
- [`plugin/26.1.x`](https://github.com/Kishku7/Chunksmith/tree/plugin/26.1.x) — Minecraft 26.1.x
- [`plugin/26.2.x`](https://github.com/Kishku7/Chunksmith/tree/plugin/26.2.x) — Minecraft 26.2.x

Within each mod branch the layout is loader-on-top: `Fabric/<version>`,
`NeoForge/<version>`, `Forge/<version>`, with shared `common/` + `nbt/` modules.

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