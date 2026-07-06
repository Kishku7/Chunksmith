# Chunksmith - shared_minecraft

The shared **mod** layer: the Minecraft-touching code common to the Fabric and NeoForge
builds. Chunksmith's Mixins and accessors live here so both loaders share one copy instead
of duplicating it.

The plugin does **not** use this module. A Bukkit server cannot apply Mixins, and on
Paper/Folia these protections are already provided by the server's Moonrise chunk system
(see [`../Plugin/README.md`](../Plugin/README.md) and
[`../docs/plugin-api-parity.md`](../docs/plugin-api-parity.md)).

## What it provides

Why big pre-gens stay safe on an unpatched vanilla server:

- **Keep-awake during generation** -- resets the empty-server pause counter while a task
  runs, so the server does not idle-pause mid-pre-gen.
- **Tick-health sampling** -- smoothed ms/tick, the signal for the adaptive I/O throttle.
- **Prompt chunk unloading / housekeeping** -- pushes pre-gen chunks to unload so memory
  stays bounded.
- **Worldgen entity-retention fix** -- skips a redundant disk read that, on vanilla, lets
  worldgen entities pile up until RAM exhausts or a save stalls the main thread.
- **Worldgen-overreach + structure-fault reporting** -- attributes generation faults to
  the responsible chunk.

These target the unpatched (vanilla) server. On Paper/Folia the equivalent work is done
natively by Moonrise, which is why the plugin omits this layer entirely.
