# Chunksmith — `26.2` (Minecraft 26.2)

[![Discord](https://img.shields.io/badge/Discord-join-5865F2?logo=discord&logoColor=white)](https://discord.gg/2ZxzbCzAHe)

This branch holds the Chunksmith **mod** build for Minecraft 26.2, organised loader-on-top.

## Platforms

- [`Fabric/`](https://github.com/Kishku7/Chunksmith/tree/26.2/Fabric) — Fabric builds
- [`NeoForge/`](https://github.com/Kishku7/Chunksmith/tree/26.2/NeoForge) — NeoForge builds
- [`Forge/`](https://github.com/Kishku7/Chunksmith/tree/26.2/Forge) — Forge builds

Shared logic lives in [`common/`](https://github.com/Kishku7/Chunksmith/tree/26.2/common) (MC-agnostic core) and [`nbt/`](https://github.com/Kishku7/Chunksmith/tree/26.2/nbt) (region NBT handling); each loader/version folder is a standalone build root that shades them in.

## Build

Each leaf folder (`<Loader>/<version>`) is an independent Gradle build — `cd` into it and run `./gradlew build`. See that folder's README for its exact toolchain.
