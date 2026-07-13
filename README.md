# Chunksmith

**Need help or found a bug?** Report it at the [Chunksmith support forum](https://github.com/Kishku7/mod_support/issues).

Chunksmith is a Minecraft chunk pre-generator that generates chunks quickly, efficiently,
and **safely**. On top of fast pre-generation it adds an adaptive I/O throttle (so it keeps
generating around the clock even with players online), region write-backpressure protection,
and worldgen diagnostics (overreach detection and structure-fault attribution).

Ships as a **Fabric, Forge, and NeoForge mod** and a **Paper / Spigot / Folia plugin**. Originally
derived from Chunky by pop4959; now developed independently. Licensed GPL-3.0.

**Source code:** [`CSv3` branch](https://github.com/Kishku7/Chunksmith/tree/CSv3) - the 3.x line, where
current development happens. The 2.x line is frozen on
[`CSv2_archive`](https://github.com/Kishku7/Chunksmith/tree/CSv2_archive).

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
- **LOD generation, on by default.** Install Distant Horizons or Voxy and Chunksmith builds their
  distant-horizon data while it pregenerates. No config file to find first - see below.
- **A re-run fills LOD holes automatically.** Pregenerated the world before you installed the renderer?
  Run the same pregen again and Chunksmith builds the LODs from the chunks it already has - it does not
  regenerate them, and it skips everything that is already done.
- **Developer API** for generation progress and completion events.

## LOD: see what you pregenerated

Chunksmith emits level-of-detail data while it pregenerates, in its own neutral format (CSLOD), and
hands it straight to a LOD renderer.

### It turns itself on

**If a LOD renderer is installed, LOD generation is ON.** Chunksmith looks for **Distant Horizons**,
**Voxy**, or a **Voxy fork** in the game when the server starts, and if one is there it builds the LOD
data as it pregenerates. There is no config key to find, nothing to switch on, and no "why are there no
LODs?" - install the renderer, install Chunksmith, pregenerate, and the distant terrain is there.

It is **also on for a dedicated server**, which is the one case where nothing local can draw an LOD:
a dedicated server runs no renderer of its own, but the store it builds is exactly what it serves to
its players (see multiplayer, below). Building it is the whole reason it is there.

If nothing in the game can use LOD data - no renderer, and not a dedicated server - Chunksmith does
not generate any, and **says so in the log** rather than leaving you guessing.

**In singleplayer, Chunksmith is the only mod you need.** The integrated server runs inside your
own game, so Chunksmith injects the LODs *directly* into your renderer - no companion mod, no
network. It registers as **Distant Horizons**' world-generator override (`lodDhOverride`), so DH
shows your pregenerated area without generating anything itself, and `/cslod dhpush` replays an
existing store into DH on demand - a world pregenerated long before you installed DH gets its LODs
after the fact, with no regeneration. Where **voxy** exists, `/cslod inject` does the same for voxy.

**In multiplayer, the LOD data has to reach the player.** The server keeps the CSLOD store; the client
downloads what it needs and feeds it to that player's Distant Horizons or voxy. Nothing else on the
server is required.

> **Multiplayer LOD delivery is being merged into Chunksmith itself.** It will ship in an upcoming
> release, and from then on players will need nothing but Chunksmith - no companion mod.
>
> That merged build is **not out yet**. The currently listed release (`3.0.0-beta-4`) still needs a
> separate client mod on each player's machine for multiplayer LOD. The standalone **Chunksmith-Client**
> mod has been **discontinued** - it is no longer developed and gets no new versions - but its existing
> builds still work with `3.0.0-beta-4` and remain downloadable
> [on Modrinth](https://modrinth.com/mod/chunksmith-client) for anyone who needs multiplayer LOD today.
>
> **Singleplayer is unaffected and never needed a companion mod.**

### A re-run fills in the missing LODs

Already pregenerated your world before you installed a LOD renderer? **Just run the same pregen
again.** Chunksmith checks the CSLOD store as well as the world, chunk by chunk:

| On disk | What Chunksmith does |
|---|---|
| No chunk | Generates it - the LOD is built on the way past |
| Chunk, but no LOD | **Loads the chunk and builds the LOD from it** - no worldgen, nothing regenerated |
| Chunk **and** LOD | Skips it entirely - no load, no write |

So the second run builds only what is missing, and a third run does nothing at all. Delete part of the
store and only those pieces come back. Nothing already done is redone, and nothing is rewritten.

The check is a single small read per region file, so it costs nothing worth measuring - a 6,500-chunk
selection spends under a millisecond deciding what it can skip. The pregen then tells you exactly what
it did: how many chunks it generated, how many LODs it built from chunks that already existed, and how
many it skipped because both were already there.

### Forcing it on, or off

`lodEnabled` in `config/chunksmith.json` is a **tristate**, not a switch:

| `lodEnabled` | What happens |
|---|---|
| `"auto"` *(default)* | **ON** if Distant Horizons, Voxy, or a Voxy fork is loaded. **ON** on a dedicated server. Off otherwise. |
| `true` | Always on, renderer or not. Useful to build the store now and install the renderer later. |
| `false` | Always off - **even with a renderer installed**. |

An explicit `true` or `false` is your decision and Chunksmith never overrides it. Whichever way it
goes, it is stated once in the server log at startup, and `/cslod status` will tell you again.

What it costs when it is on: **~5.8 KB per chunk** on disk and a **~16% slower** pre-generation. Plain
region files - no native database, nothing extra to install.

### Where LOD is available

| | Distant Horizons | voxy |
|---|---|---|
| **Fabric** | 1.20.1, 1.21.1, 1.21.11, 26.1, 26.2, 26.3 | 1.21.11, 26.1, 26.2, 26.3 |
| **NeoForge** | 1.21.1, 1.21.11, 26.1, 26.2 | - |
| **Forge** | 1.20.1 | - |

Those are the versions the renderers themselves ship on - Chunksmith never claims a renderer it
cannot feed. **voxy is Fabric-only** (upstream builds no NeoForge or Forge jar) and exists only on
**1.21.11 and 26.x**. **Distant Horizons works everywhere** on the list - Chunksmith needs **DH
2.3.0-b or newer**, with no upper bound. The Paper / Spigot / Folia plugin has **no LOD**: there is no
client-side renderer to hand data to on that platform. The remaining mod versions (1.20.4, 1.20.6,
1.21.4, 1.21.5, 1.21.8, 1.21.10) carry everything except LOD.

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
