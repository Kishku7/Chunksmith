# Chunksmith - 26.x line (Minecraft 26.1 - 26.3)

Chunksmith **mod** builds for the Minecraft 26 line, unified on a single branch.
One loader source tree builds every supported 26.x Minecraft version -- edit once,
ship the whole line.

## Platforms

- [`Fabric/`](https://github.com/Kishku7/Chunksmith/tree/26/Fabric) - one source, builds MC 26.1, 26.2, and 26.3
- [`NeoForge/`](https://github.com/Kishku7/Chunksmith/tree/26/NeoForge) - NeoForge builds (MC 26.2 today; 26.3 pending -- NeoForge has no 26.3 release yet)

Shared logic: [`common_26/`](https://github.com/Kishku7/Chunksmith/tree/26/common_26) (MC-agnostic core) + [`nbt/`](https://github.com/Kishku7/Chunksmith/tree/26/nbt) (region NBT), shaded into each loader build. (`common`/`nbt` are version-agnostic; the 1.20.x / 1.21.x lines are frozen on their own branches.)

## Build (Fabric)

From the repo root, build every 26.x target at once:

    pwsh build-all-fabric.ps1            # -> dist/Chunksmith-Fabric-<ver>+mc<26.x>.jar

or a single target:

    pwsh build-all-fabric.ps1 26.2

or directly with Gradle (defaults target the dev tip, 26.3):

    cd Fabric
    ./gradlew build -PmcVersion=26.2 -PfabricApiVersion=0.152.1+26.2

| MC target | minecraft | fabric-api | declared compat |
|-----------|-----------|------------|-----------------|
| 26.1      | 26.1.2          | 0.150.0+26.1.2 | >=26.1 <26.2 |
| 26.2      | 26.2            | 0.152.1+26.2   | >=26.2-      |
| 26.3      | 26.3-snapshot-1 | 0.153.1+26.3   | >=26.3-      |

The compiled code is identical across the three (mojmap-native, no remap); only the
`fabric.mod.json` compatibility range differs per target.