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
- **Multiplayer LOD, built in.** Players joining your server download the pregenerated LOD data and see
  the whole world at distance, without ever having walked it. **No companion mod** - the same Chunksmith
  jar does it, on the server and on the client.
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

**In multiplayer, the LOD data has to reach the player - and as of `3.1.0-beta-1`, Chunksmith does that
itself.** Put the same jar on the server and on the client. The server keeps the CSLOD store; the client
downloads what it needs and feeds it to that player's Distant Horizons or voxy. **There is no companion
mod any more.**

It arrives at network speed. The store is already plain region files, so the server does not stream them
- it **serves** them, over an HTTP backchannel on the game port + 1, opened automatically with nothing for
you to configure. If that port cannot be bound or cannot be reached, Chunksmith says so and drips the same
bytes down the game connection instead: slower, but it always works, and it never breaks the session.

- **The store is the cache.** Re-join and nothing is downloaded twice.
- **It follows you.** Walk toward terrain the server pregenerated but had not sent, and it is fetched on
  the way - the pull is not a one-shot at join.
- **It only sends what you can draw.** The client tells the server its renderer's actual LOD distance, and
  the server ships the regions inside it and no more.
- **It keeps itself in step.** While you play, the client and the server compare a small checksum every few
  minutes; if they differ, the client fetches only what changed. Terrain that a running pre-generation just
  finished shows up **without a relog and without moving** (see below).

### Server and client must be on the same version

**From `3.1.0-beta-4` on, the server and every client must be on `3.1.0-beta-4` or later.** That release
changed the LOD network protocol (v1 -> v2), and there is no compatibility path: the number the two sides
compare to decide "do I already have this region?" is exactly what had to change to fix a bug that could
take a server down.

A mismatched pair does not crash and does not hang. Both sides notice, both refuse, and **both say so in
the log** - the distant terrain is simply not delivered. If you update the server, update the clients;
if you update your client, the server has to come with you.

### It keeps up while you play

The client asks the server for a one-line summary of the LOD regions in your view - a count and a single
folded checksum - and compares it with its own. If they match, nothing happens at all. If they do not, it
pulls the region list and downloads **only the difference**.

That one mechanism covers all three ways the two sides can drift apart: the server generated more terrain,
a region you already have changed, or you lost region files off your own disk. It costs 22 bytes out and
34 bytes back, and the server answers it without reading a single byte of the store.

| Key | Where | Default |
|---|---|---|
| `sync-interval-seconds` | `config/chunksmith-lod.properties` (client) | **300** |

The file is written with defaults and comments the first time the client runs. **Anything below 30 is
clamped to 30**, deliberately: a config value is a suggestion, and a one-second poll must not turn into a
denial of service against a server that is already busy pre-generating. There is no settings screen for it
yet.

### One mod, all of it

| What you are doing | What you install |
|---|---|
| Singleplayer | **Chunksmith.** That is all - it always was. |
| Playing on a server | **Chunksmith, on the server and on the client.** Same jar. |
| Running a server, pre-generation only | **Chunksmith on the server.** Nothing new loads; a dedicated server never touches the client half. |

> **The standalone Chunksmith-Client mod is discontinued.** Its job is now part of Chunksmith, and as of
> `3.1.0-beta-4` an old copy of it **no longer works** - it speaks the v1 LOD protocol, and a `3.1.0-beta-4`
> server will refuse it and tell it so. There is no reason to keep it in any case, and **you cannot run
> both**: they register the same network channel, and the loader will refuse to start and tell you to remove
> one. Delete Chunksmith-Client; Chunksmith does the job alone.

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

### voxy forks

voxy gets forked a lot, so Chunksmith feeds **upstream voxy and its forks through one adapter**: it looks
for the mod id `voxy` (every fork keeps it), reads the fork's own render-distance setting, and hands data
to voxy's own ingest API. There is no per-fork code and no fork-specific list to maintain.

Forks are third-party builds of an All-Rights-Reserved mod. **Chunksmith does not ship, mirror, endorse or
link any of them** - the table below is a test record, nothing more. We ran the real jars on 2026-07-13 and
looked at the screen:

| voxy build | MC | Result |
|---|---|---|
| **voxy** (upstream, MCRcortex) | 1.21.11, 26.1.2, 26.2 | **Works.** Detected, render distance read (8192 blocks), distant terrain drawn in singleplayer and in multiplayer. |
| **mia-edition** (ggonzaDNG) | 1.21.11 | **Works.** Same, singleplayer and multiplayer. |
| **voxy 26.2 branch** (NHblock714) | 26.2 | **Works.** Singleplayer verified. |
| **voxy-26.2** (Paulem79) | 26.2 | **Works.** Detected, distance read, LODs ingested. |
| **Vulkan-Voxy** (SpinGiantCRM) | 26.1.2 | **Chunksmith's side works** - detected, distance read, LODs ingested into its database - but its Vulkan renderer drew no distant terrain on either machine we could test it on. That is between you and the fork; Chunksmith hands it the data. |
| **m-series support** (srjefers) | 1.21.11 | **Chunksmith reads it correctly, but the fork does not work.** Its renderer is based on an old voxy that predates MC 1.21.9's render rework, and it throws `IllegalStateException: Cannot use the default framebuffer` the moment it has anything to draw - **with Chunksmith removed as well**. Nothing we can fix from here. |

If a fork ever changes something Chunksmith depends on, **Chunksmith says so in the log** - once, in plain
words, naming what it could not read and what it fell back to. It will never quietly send you less terrain.

### NeoForge and Forge: Distant Horizons only

There is no voxy for NeoForge or Forge on the modern versions - not from upstream, and not from a fork.
Every "NeoForge voxy" out there is a Fabric jar loaded through **Sinytra Connector**, and Connector only
supports **1.20.1 / 1.21 / 1.21.1**. There is no Connector for 1.21.11 or 26.x, so there is nothing to
repackage and nothing for Chunksmith to feed. On NeoForge and Forge, Chunksmith's LOD is **Distant
Horizons only** - a limit of the ecosystem, not of Chunksmith.

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
