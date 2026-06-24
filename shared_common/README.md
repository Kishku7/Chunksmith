# Chunksmith - shared_common (chunksmith-common)

The Minecraft-agnostic core of Chunksmith, and the single source of truth for everything
that does not touch Minecraft internals. Shared verbatim by the Fabric and NeoForge mods
AND the Bukkit/Paper/Folia plugin (where it is included as the `chunksmith-common` module).

## Contents

- Command tree, argument parsing, and tab-completion/suggestions.
- Generation tasks, shapes, iteration order, and progress/rate/ETA.
- World trimming.
- Config model and language/translation.
- Region NBT reading (folded in here; there is no separate `nbt` module).
- The public developer API (generation progress/complete events).

## How it stays platform-neutral

The core talks to the world only through platform interfaces -- `World`, `Player`,
`Server`, `Border`, `Config`, `Sender` -- which each loader and the plugin implement. No
Minecraft, Fabric, NeoForge, or Bukkit types appear in this module, which is exactly why a
single copy serves every platform. It is shaded into each mod jar and consumed as a Gradle
project dependency by the plugin.
