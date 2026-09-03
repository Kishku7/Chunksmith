# Chunksmith

**Pre-generate your world -- and see it.**

Chunksmith generates chunks ahead of time so players never wait on worldgen. What makes it different
is that the *same pass* also builds the distant-horizon data for Distant Horizons or voxy. One run,
two results: a world that loads instantly, and a world you can see to the edge of.

Originally derived from [Chunky](https://github.com/pop4959/Chunky) by pop4959; developed
independently since. Everything Chunky did, Chunksmith still does.

**[Full documentation is on the wiki](https://github.com/Kishku7/Chunksmith/wiki)** -- every
setting, every command, and walkthroughs for pregenerating, trimming, and getting multiplayer LOD
working.

**[Report a bug or ask a question](https://github.com/Kishku7/mod_support/issues)**

---

## What Chunksmith adds

### It builds your LOD data while it pregenerates

No second pass. No re-reading region files. No separate LOD pregen afterwards. Install Distant
Horizons or voxy, run a pregen, and the distant terrain is simply there.

**It turns itself on.** If a renderer is installed, LOD generation is on. There is no config key to
hunt for.

**Already pregenerated?** Run the same pregen again. Chunksmith builds LODs from the chunks it
already has -- it does not regenerate them, and it skips everything already done. Pregenerate today,
install a renderer next month, lose nothing.

**Multiplayer LOD, with no companion mod.** Players joining your server download the pregenerated LOD
data and see the whole world at distance without ever having walked it. The same jar is both halves --
server and client. The Paper/Spigot plugin serves it too, so a modded client against a plugin server
works.

### It keeps generating while people are playing

Most pregenerators make you choose between speed and a playable server. Chunksmith measures what it
*adds* to tick time rather than steering on absolute tick time -- so a server already running hot for
its own reasons doesn't throttle Chunksmith to nothing for load it didn't cause.

Every online player shrinks the allowance further, so an empty server runs flat out and a busy one
actively gives ground. If the server still can't sustain it, the run **pauses itself with a stated
reason and resumes when things recover**. Your `/cs pause` always outranks it.

### It is measurably faster

`dispatchMaxConcurrent` controls how many chunk requests stay in flight, and it scales to your CPU
instead of a fixed cap. On an 8-core dedicated server, raising it from the old fixed 50 to 200
measured **+39% -- 31.6 to 43.9 chunks/sec**. Settable live.

### Other mods' structures actually get built

A pregenerator that drops each chunk the instant it's generated breaks every mod that reacts to "a
new chunk appeared" and then builds on a later tick -- the ground is already gone by the time it
looks. Measured with Millenaire on a 1.21.1 pregen: **309 placement attempts, 309 deferrals, zero
villages.**

Chunksmith holds each chunk until all eight neighbours exist, then releases it. Nothing here is
mod-specific -- the rule is about chunks, so anything that builds on new land benefits.

### It tells you when worldgen misbehaves

Overreach detection and structure-fault attribution, so a broken worldgen mod is something you can
identify rather than something you merely suffer.

### Everything is live

Every setting is readable and settable with `/cs set` -- no restart, no editing files, no server
downtime. That's a rule the project holds itself to, with a test that fails if a setting is added
without a command for it.

---

## Everything Chunky did

Flexible shapes (square, circle, diamond, triangle, star, and more), centred on coordinates, world
spawn, or the world border. Multi-world. Live progress, rate, ETA and an optional boss bar. Pause,
continue, cancel, and continue-on-restart. World trimming. A developer API for progress and
completion events.

---

## Installing

**Required on the server** -- or in single-player, where your own game *is* the server. The client
install is **optional**.

You only need it on the client for **multiplayer LOD**: to see pregenerated distant terrain on a
server you haven't walked. Pre-generation alone needs nothing on the client.

Ships as a **Fabric, Forge, and NeoForge mod** and a **Paper / Spigot plugin**. (Folia is no longer
tested: the plugin still carries its Folia support and as far as anyone knows it still works, but
nothing verifies that any more, so it is not a promise this project makes.)

**Server and client should run the same Chunksmith version.** The LOD wire protocol is versioned and
a mismatch is refused with a clear message rather than failing strangely.

Renderer support, per loader and Minecraft version, is on the
**[Supported renderers](https://github.com/Kishku7/Chunksmith/wiki/Supported-Renderers)** page --
including the voxy forks, and which mods Chunksmith refuses to load beside.

## Quick start

```
/cs world <world>     # pick the world
/cs spawn             # centre on spawn
/cs radius 5000       # how far out
/cs start             # go
```

`/cs progress` to watch it, `/cs pause` and `/cs continue` as needed. The
[wiki](https://github.com/Kishku7/Chunksmith/wiki) has the rest.

## Source

[`CSv3-Current`](https://github.com/Kishku7/Chunksmith/tree/CSv3-Current) -- the 3.x line, where
current development happens. The 2.x line is frozen on
[`CSv2_archive`](https://github.com/Kishku7/Chunksmith/tree/CSv2_archive).

## License

GPL-3.0-only. Original Chunky (c) pop4959. Chunksmith modifications (c) Kishku7.
