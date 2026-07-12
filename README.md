# Chunksmith

**Need help or found a bug?** Report it at the [Chunksmith support forum](https://github.com/Kishku7/mod_support/issues).

Chunksmith is a Minecraft chunk pre-generator that generates chunks quickly, efficiently,
and **safely**. On top of fast pre-generation it adds an adaptive I/O throttle (so it keeps
generating around the clock even with players online), region write-backpressure protection,
and worldgen diagnostics (overreach detection and structure-fault attribution).

Ships as a **Fabric, Forge, and NeoForge mod** and a **Paper / Spigot / Folia plugin**. Originally
derived from Chunky by pop4959; now developed independently. Licensed GPL-3.0.

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
- **LOD generation.** Turn a pregenerated world into a distant-horizon view - see below.
- **Developer API** for generation progress and completion events.

## LOD: see what you pregenerated

Chunksmith can emit level-of-detail data while it pregenerates, in its own neutral format
(CSLOD), and hand it straight to a LOD renderer. Off by default (`lodEnabled`).

**In singleplayer, Chunksmith is the only mod you need.** The integrated server runs inside your
own game, so Chunksmith injects the LODs *directly* into your renderer - no companion mod, no
network. It registers as **Distant Horizons**' world-generator override (`lodDhOverride`), so DH
shows your pregenerated area without generating anything itself, and `/cslod dhpush` replays an
existing store into DH on demand - a world pregenerated long before you installed DH gets its LODs
after the fact, with no regeneration. Where **voxy** exists, `/cslod inject` does the same for voxy.

**In multiplayer, players install [Chunksmith-Client](https://modrinth.com/mod/chunksmith-client).**
The server keeps the CSLOD store; the client downloads what it needs and feeds it to that player's
Distant Horizons or voxy. Nothing else on the server is required.

### Where LOD is available

| | Distant Horizons | voxy |
|---|---|---|
| **Fabric** | 1.20.1, 1.21.1, 1.21.11, 26.x | 1.21.11, 26.x |
| **NeoForge** | 1.21.1, 1.21.11, 26.x | - |
| **Forge** | 1.20.1 | - |

Those are the versions the renderers themselves ship on - Chunksmith never claims a renderer it
cannot feed. **voxy is Fabric-only** (upstream builds no NeoForge or Forge jar) and exists only on
**1.21.11 and 26.x**. The Paper / Spigot / Folia plugin has no LOD: there is no client-side renderer
to hand data to on that platform. The remaining mod versions (1.20.4, 1.20.6, 1.21.4, 1.21.5,
1.21.8, 1.21.10) carry everything except LOD.

### Conflicts

Do not run Chunksmith's LOD alongside another server-side LOD provider - `lss`, `voxyserver`, or
`lodserver`. They inject into the same renderer over the same channels, and whichever gets there
first wins; the result is missing or corrupted LODs, not an error message. Pick one. Chunksmith
declares those three as incompatible so your loader tells you before you find out the hard way.

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
