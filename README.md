# Chunksmith — `26.2` (Minecraft 26.2)

[![Discord](https://img.shields.io/badge/Discord-join-5865F2?logo=discord&logoColor=white)](https://discord.gg/2ZxzbCzAHe)

Chunksmith **mod** build for Minecraft 26.2, organised loader-on-top.

## Platforms

- [`Fabric/`](https://github.com/Kishku7/Chunksmith/tree/26.2/Fabric) — Fabric builds
- [`NeoForge/`](https://github.com/Kishku7/Chunksmith/tree/26.2/NeoForge) — NeoForge builds

Shared logic: [`common/`](https://github.com/Kishku7/Chunksmith/tree/26.2/common) (MC-agnostic core) + [`nbt/`](https://github.com/Kishku7/Chunksmith/tree/26.2/nbt) (region NBT). Each `<Loader>/<version>` is a standalone build root that shades them in.

## Build

`cd` into a leaf folder and run `./gradlew build`; see its README for the exact toolchain.
