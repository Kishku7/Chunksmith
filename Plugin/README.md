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

Credits: original Chunky by pop4959; the Paper/Folia chunk-system work referenced above is
Moonrise (Spottedleaf). Findings detail: [`../.docs/plugin-api-parity.md`](../.docs/plugin-api-parity.md).
