# Chunksmith — `1.21.x` (Minecraft 1.21–1.21.11)


Chunksmith **mod** build for Minecraft 1.21–1.21.11, organised loader-on-top.

## Platforms

- [`Fabric/`](https://github.com/Kishku7/Chunksmith/tree/1.21.x/Fabric) — Fabric builds
- [`NeoForge/`](https://github.com/Kishku7/Chunksmith/tree/1.21.x/NeoForge) — NeoForge builds
- [`Forge/`](https://github.com/Kishku7/Chunksmith/tree/1.21.x/Forge) — Forge builds

Shared logic: [`common/`](https://github.com/Kishku7/Chunksmith/tree/1.21.x/common) (MC-agnostic core) + [`nbt/`](https://github.com/Kishku7/Chunksmith/tree/1.21.x/nbt) (region NBT). Each `<Loader>/<version>` is a standalone build root that shades them in.

## Build

`cd` into a leaf folder and run `./gradlew build`; see its README for the exact toolchain.
