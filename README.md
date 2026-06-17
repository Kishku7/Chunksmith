# Chunksmith — `1.20.x` (Minecraft 1.20.1–1.20.6)

[![Discord](https://img.shields.io/badge/Discord-join-5865F2?logo=discord&logoColor=white)](https://discord.gg/2ZxzbCzAHe)

Chunksmith **mod** build for Minecraft 1.20.1–1.20.6, organised loader-on-top.

## Platforms

- [`Fabric/`](https://github.com/Kishku7/Chunksmith/tree/1.20.x/Fabric) — Fabric builds
- [`NeoForge/`](https://github.com/Kishku7/Chunksmith/tree/1.20.x/NeoForge) — NeoForge builds
- [`Forge/`](https://github.com/Kishku7/Chunksmith/tree/1.20.x/Forge) — Forge builds

Shared logic: [`common/`](https://github.com/Kishku7/Chunksmith/tree/1.20.x/common) (MC-agnostic core) + [`nbt/`](https://github.com/Kishku7/Chunksmith/tree/1.20.x/nbt) (region NBT). Each `<Loader>/<version>` is a standalone build root that shades them in.

## Build

`cd` into a leaf folder and run `./gradlew build`; see its README for the exact toolchain.
