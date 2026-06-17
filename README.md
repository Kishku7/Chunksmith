# Chunksmith — `1.21.x` (Minecraft 1.21–1.21.11)

[![Discord](https://img.shields.io/badge/Discord-join-5865F2?logo=discord&logoColor=white)](https://discord.gg/2ZxzbCzAHe)

This branch holds the Chunksmith **mod** build for Minecraft 1.21–1.21.11, organised loader-on-top.

## Platforms

- [`Fabric/`](https://github.com/Kishku7/Chunksmith/tree/1.21.x/Fabric) — Fabric builds
- [`NeoForge/`](https://github.com/Kishku7/Chunksmith/tree/1.21.x/NeoForge) — NeoForge builds
- [`Forge/`](https://github.com/Kishku7/Chunksmith/tree/1.21.x/Forge) — Forge builds

Shared logic lives in [`common/`](https://github.com/Kishku7/Chunksmith/tree/1.21.x/common) (MC-agnostic core) and [`nbt/`](https://github.com/Kishku7/Chunksmith/tree/1.21.x/nbt) (region NBT handling); each loader/version folder is a standalone build root that shades them in.

## Build

Each leaf folder (`<Loader>/<version>`) is an independent Gradle build — `cd` into it and run `./gradlew build`. See that folder's README for its exact toolchain.
