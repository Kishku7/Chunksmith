# Chunksmith - Plugin (Bukkit / Paper / Folia)

The Chunksmith **plugin** is the Bukkit-family build. It shares the same MC-agnostic
core as the mod (`shared_common`, included here as `chunksmith-common`), so command
parsing, task scheduling, shapes, trimming, progress, and the public API are the exact
same code the Fabric/NeoForge mod runs.

## Modules

- `bukkit/` (`chunksmith-bukkit`) - the plugin entrypoint and the Bukkit implementations
  of the platform interfaces (world, player, config, border, sender, server).
- `platform/` (`chunksmith-platform`) - thin Paper/Folia helpers (async chunk loading,
  tick-time, region schedulers) behind `Reflection.classExists` guards so one jar runs on
  Spigot, Paper, and Folia.
- `chunksmith-common` -> `../shared_common` - the shared MC-agnostic core (not a copy).

Build: `./gradlew build` from this folder -> `bukkit/build/libs/Chunksmith-Bukkit-<ver>.jar`.

## Parity with the mod, and what we deliberately do NOT re-implement

The mod (Fabric/NeoForge) carries a set of Mixins into Minecraft server internals to make
large pre-generations safe on an otherwise-unpatched (vanilla) server. A Bukkit plugin
cannot Mixin, so the natural question is whether the plugin loses those protections. It
does not - on the platforms people actually run, the critical items have parity, because
either the plugin reaches them through the public API or the **server already provides
them**. We verified this against decompiled Paper 26.1.2 and 26.2 source; the relevant
Moonrise chunk-system classes are byte-identical between those two versions.

### Achieved through the public API (true parity)

- **Adaptive I/O throttle.** The mod samples main-thread ms/tick via a Mixin; the plugin
  reads `Server.getAverageTickTime()`. The shared core consumes whichever signal the
  platform supplies, so the throttle behaves the same.
- **Async generation.** The mod drives chunks to full via the chunk cache; the plugin uses
  Paper's `World.getChunkAtAsync(x, z, true)`.
- **Worldgen-overreach / structure-fault diagnostic.** The mod attributes faults via
  Mixins; the plugin installs a Log4j2 filter that captures the vanilla "Detected setBlock
  in a far chunk" line and routes it to the same reporter. Best-effort but functionally
  equivalent for the operator.

### Provided by the server, NOT by us (we do not claim these fixes)

These are real problems on an unpatched vanilla server, which is why the mod fixes them.
On Paper/Folia they are already solved by the server's rewritten (Moonrise) chunk system,
so the plugin intentionally does nothing here - re-implementing them would be claiming a
fix for a problem someone else already fixed.

- **Idle-timeout "keep-awake" is a server feature, not a plugin feature.** Vanilla pauses
  an empty server (`pause-when-empty-seconds`) even mid-generation, so the mod resets the
  empty-tick counter while a task runs. Paper/Folia do **not** idle-pause while generation
  work is pending - verified live: a no-player radius-500 pre-gen on Paper 26.1.2 with
  `pause-when-empty-seconds=10` ran to completion without pausing. The plugin therefore
  leaves Paper/Folia alone. (On non-Paper Bukkit it still sets `pause-when-empty-seconds=0`
  in `server.properties`, the same approach upstream uses.)
- **Worldgen entity retention.** Vanilla's `PersistentEntitySectionManager` blocks freeing
  a fresh chunk's entities behind a disk read, so RAM climbs and saves stall during pre-gen;
  the mod works around it. Moonrise replaces that subsystem entirely (`NewChunkHolder` +
  `ChunkEntitySlices` + async entity load/unload) - there is no blocking read-before-free,
  so the defect does not exist on Paper/Folia. The plugin adds nothing.
- **Prompt chunk unloading.** Vanilla needs a nudge to unload pre-gen chunks promptly, which
  the mod provides. Moonrise drains a dedicated `ChunkUnloadQueue` every tick, unloading a
  bounded fraction per pass with throttled incremental autosave and async writes - prompt
  and bounded natively. The plugin relies on the server (it just drops its chunk tickets).

In short: the mod's server-internal Mixins exist for unpatched vanilla servers. On
Paper/Folia the platform already does that work - usually better, because it is threaded
and bounded - so the plugin is thinner by design, not less capable.

## LOD: the plugin generates it AND serves it (3.15.0)

A player on a Paper/Spigot/Folia server running this plugin sees pregenerated distant terrain,
provided that player has the Chunksmith **mod** and a renderer (Distant Horizons or voxy) on their
own client. The server needs no renderer -- it never draws anything, it only ships the store.

Before 3.15.0 the plugin generated a CSLOD store and could not deliver it. Every piece of the
delivery machinery had in fact been in the jar since 3.2.0, because it lives in `shared_common`:
the wire format, the token store, the HTTP backchannel. Nothing registered a channel or started the
server, so it was all unreachable. `CsLodServerBukkit` is the connection that was missing.

- Lives in `shared_plugin/`, so **all three plugin lines (1.20.x / 1.21.x / 26.x) have it.** There is
  no per-line gate and no config to turn it on: if LOD is on, the channel is registered and the
  backchannel port is opened.
- `lod-backchannel-port` in `config.yml` behaves exactly as it does on the mod (0 = derive game
  port + 1). The startup line names the port in force, which is the port your players must reach.
- Region selection goes through `CsLodIndexScan`, the **same** code the Fabric/NeoForge server runs,
  so "which regions can this player see?" cannot drift between platforms.

### Two traps worth keeping written down

**Bukkit silently drops a reply on a channel the client never announced.** A Bukkit server only
delivers a plugin message on a channel the client registered via `minecraft:register`, and discards
anything else with no exception, no log line and no packet. A modern Fabric client never sends that
announcement, so the server could hear the client perfectly while the client heard nothing back. The
plugin now registers the outgoing channel for a player who has already spoken to it on that channel
-- proof enough that they speak it.

**Answering the hello is not serving LOD.** The first cut of this replied to `ServerHello` and
stopped, which presents exactly like success: greeting logged, port bound, download token minted,
and `0 files, 0 bytes` transferred, because the client was waiting on a region index that never
came. The index request and the periodic sync request are both answered now, off the main thread,
filtered to the draw distance the client reported. Proven on Paper 26.2 against a Fabric client with
voxy: 16 regions, 27 MB, 5928 chunks.

### The one real gap against the mod

**No in-band fallback.** When the backchannel port cannot be bound or reached, the mod falls back to
streaming the same bytes down the game connection -- slower, but it works. The plugin does not do
this yet, so a blocked or unreachable port means players get **nothing** rather than something slow.
It is logged plainly rather than failing quietly; the fix is to open the port, or set one the host
allows.

Credits: original Chunky by pop4959; the Paper/Folia chunk-system work referenced above is
Moonrise (Spottedleaf). Findings detail: [`../docs/plugin-api-parity.md`](../docs/plugin-api-parity.md).
