# Chunksmith

Chunksmith is a Minecraft chunk pre-generator that generates chunks quickly,
efficiently, and safely. On top of fast pre-generation it adds adaptive I/O
throttling, region write-backpressure protection, and worldgen diagnostics
(overreach + structure-fault attribution).

Ships as a **Fabric + NeoForge mod** and a **Paper / Spigot / Folia plugin**.

Originally derived from Chunky by pop4959; now developed independently as
Chunksmith. Licensed GPL-3.0.

**Download:** https://modrinth.com/mod/chunksmith

## Repository layout

`main` is just this landing page. The code lives on per-target branches — one
per Minecraft version family, split by build type.

### Mod (Fabric + NeoForge)
- [`mod/1.20.x`](../../tree/mod/1.20.x)
- [`mod/1.21.x`](../../tree/mod/1.21.x)
- [`mod/26.1.x`](../../tree/mod/26.1.x)
- [`mod/26.2.x`](../../tree/mod/26.2.x) — pre-release line

### Plugin (Paper / Spigot / Folia)
- [`plugin/1.20.x`](../../tree/plugin/1.20.x)
- [`plugin/1.21.x`](../../tree/plugin/1.21.x)
- [`plugin/26.1.x`](../../tree/plugin/26.1.x)
- [`plugin/26.2.x`](../../tree/plugin/26.2.x) — pre-release line

Each branch targets its own Minecraft family; within a branch, build variants
cover any sub-versions that need a hard split. See each branch's build files for
the exact Minecraft versions and loaders it produces.

## License

GPL-3.0-only. Original Chunky (c) pop4959. Chunksmith modifications (c) Kishku7.