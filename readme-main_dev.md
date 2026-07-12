# Chunksmith

<!--
  STAGING COPY OF THE LANDING-PAGE README.

  This file is the `main` branch README-in-waiting. It is NOT the build guide (that is README.md on
  this branch). When `dev` goes live, this file's contents REPLACE `main`'s README.md, and this file
  is deleted from the code branch.

  Keep it a sales pitch: what Chunksmith is and why you would want it. No build instructions.
-->

**Need help or found a bug?** Report it at the [Chunksmith support forum](https://github.com/Kishku7/mod_support/issues).

Chunksmith is a Minecraft chunk pre-generator that generates chunks quickly, efficiently,
and **safely**. On top of fast pre-generation it adds an adaptive I/O throttle (so it keeps
generating around the clock even with players online), region write-backpressure protection,
worldgen diagnostics (overreach detection and structure-fault attribution), and - new - it can
**build your level-of-detail data while it pregenerates**.

Ships as a **Fabric, Forge, and NeoForge mod** and a **Paper / Spigot / Folia plugin**. Originally
derived from Chunky by pop4959; now developed independently. Licensed GPL-3.0.

**Source code:** [`minecraft-1.20-26.3` branch](https://github.com/Kishku7/Chunksmith/tree/minecraft-1.20-26.3)

## Why Chunksmith

- **Fast.** Pre-generates chunks ahead of time so they are ready before players arrive,
  eliminating the lag of on-demand generation.
- **Safe under load.** An adaptive throttle watches server tick-health and backs off
  automatically, so generation can run while players are online without tanking TPS.
- **Builds your LODs too.** The same pass that generates the world can generate the distant-horizon
  data for it - for **Voxy** *and* **Distant Horizons**, from one store. See below.
- **Flexible shapes.** Generate by square, circle, diamond, triangle, star, and more -
  centered on coordinates, world spawn, or the world border, by radius or diameter.
- **Multi-world**, with live progress, rate, ETA, and an optional boss bar.
- **Resumable.** Pause, continue, cancel, and continue-on-restart - progress is saved.
- **World trimming.** Delete chunks outside a selected region.
- **Developer API** for generation progress and completion events.

## LOD generation

*(Off by default. Ships on Fabric 1.20.1, 1.21.1, 1.21.11 and 26.x; NeoForge 1.21.1, 1.21.11 and 26.x;
Forge 1.20.1 - the versions with a client-side LOD renderer to serve.)*

Pre-generating a world and then building LODs for it usually means doing the work **twice**: once to
generate the chunks, then again while an LOD mod re-reads every region file back off disk. Chunksmith
does it **once** - it captures the LOD data at the moment each chunk is generated, while it is already
in memory and already lit.

The data is written in Chunksmith's own neutral format, so a single store can feed every LOD consumer:

| Consumer | How it is fed |
|----------|---------------|
| [Voxy](https://modrinth.com/mod/voxy) | Fed live during pre-generation, **and** replayable afterwards with `/cslod inject` (Fabric 26.x - Voxy ships nowhere else) |
| [Distant Horizons](https://modrinth.com/mod/distanthorizons) | Chunksmith becomes DH's world-generator and serves it straight from the store - DH generates nothing (Fabric 26.x) |
| Remote clients | Served on request, over an authenticated side-channel, to any player running Chunksmith-Client |

### The part worth reading twice

**The LOD mod does not have to be installed when you pre-generate.**

Pre-generate a world today with nothing but Chunksmith. Install Voxy or Distant Horizons a month from
now. Run `/cslod inject` (Voxy), or simply load the world (Distant Horizons) - and the LODs are
**already there**. No regeneration, no re-scan, no touching the world again.

That works because Chunksmith stores plain vanilla registry data - full block states, per-voxel
biomes, and sky and block light kept separate and carried even through open air - rather than any one
mod's private internal format. Both mods' data models can be rebuilt from it losslessly, and neither
mod's format changes can break your store.

### What it costs

Measured on a 1089-chunk pre-generation, MC 26.1.2:

| | |
|---|---|
| Chunksmith's LOD store | **~5.8 KB per chunk** |
| Voxy's own database, same chunks | ~43 KB per chunk (**7.4x larger**) |
| Pre-generation slowdown | **~16%** |

Plain region files. No native database, no lock, nothing to install.

### Usage

In `config/chunksmith.json`:

    "lodEnabled": true,        // build LOD data while pre-generating (and feed Voxy if installed)
    "lodDhOverride": true      // additionally serve Distant Horizons from the store

Commands (op):

    /cslod status              // where the store is, how big, and who is being served
    /cslod inject              // replay the store into Voxy

Notes:

- Voxy requires an exact matching Sodium version and will not load without it.
- `lodDhOverride` **replaces** Distant Horizons' own distant generator for that world: your
  pre-generated area appears instantly, and area you have not pre-generated stays empty. That is the
  right trade for a pre-generated world and the wrong one otherwise - hence opt-in.
- Neither Voxy nor Distant Horizons is bundled with Chunksmith. Both are optional; install whichever
  you already use.

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
