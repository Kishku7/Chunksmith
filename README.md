# Chunksmith

**Need help or found a bug?** Report it at the [Chunksmith support forum](https://github.com/Kishku7/mod_support/issues).

Chunksmith is a Minecraft chunk pre-generator that generates chunks quickly, efficiently,
and **safely**. On top of fast pre-generation it adds an adaptive I/O throttle (so it keeps
generating around the clock even with players online), region write-backpressure protection,
and worldgen diagnostics (overreach detection and structure-fault attribution).

Ships as a **Fabric + NeoForge mod** and a **Paper / Spigot / Folia plugin**. Originally
derived from Chunky by pop4959; now developed independently. Licensed GPL-3.0.

**Download:** https://modrinth.com/mod/chunksmith
**Source code:** [`minecraft-1.20-26.3` branch](https://github.com/Kishku7/Chunksmith/tree/minecraft-1.20-26.3)

## Why Chunksmith

- **Fast.** Pre-generates chunks ahead of time so they are ready before players arrive,
  eliminating the lag of on-demand generation.
- **Safe under load.** An adaptive throttle watches server tick-health and backs off
  automatically, so generation can run while players are online without tanking TPS.
- **Flexible shapes.** Generate by square, circle, diamond, triangle, star, and more -
  centered on coordinates, world spawn, or the world border, by radius or diameter.
- **Multi-world**, with live progress, rate, ETA, and an optional boss bar.
- **Resumable.** Pause, continue, cancel, and continue-on-restart - progress is saved.
- **World trimming.** Delete chunks outside a selected region.
- **Developer API** for generation progress and completion events.

## Usage

The primary command is `/cs` (alias `/chunksmith`); `/chunky` and `/cy` are deprecated
aliases that redirect to `/cs`. The mod/plugin is op-gated on servers; on Bukkit it uses
the `chunksmith.command.*` permission namespace (legacy `chunky.command.*` still works).

Common workflow:

- `/cs world <world>` - choose the target world (defaults to the current overworld)
- `/cs spawn` or `/cs center <x> <z>` - set the generation center
- `/cs radius <blocks>` - set the radius (or `/cs worldborder` to use the world border)
- `/cs start` - begin generating
- `/cs pause` / `/cs continue` - pause and resume (progress is saved)
- `/cs cancel` then `/cs confirm` - stop and discard the current/saved task

Generation is throttled automatically against server tick-health, so it can run while
players are online.

## License

GPL-3.0-only. Original Chunky (c) pop4959. Chunksmith modifications (c) Kishku7.
