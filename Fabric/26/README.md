# Chunksmith - Fabric (Minecraft 26.1 - 26.3)

The Fabric mod build. One version-agnostic source builds every 26.x target; the only
per-version differences are the `minecraft` + `fabric-api` dependency versions and the
`fabric.mod.json` compatibility range. The compiled code is identical across versions
(mojmap-native, no remap).

Shared code: the MC-agnostic core is [`../shared_common`](../shared_common); the
Minecraft-touching mod layer (Mixins/accessors that keep big pre-gens safe on vanilla) is
[`../shared_minecraft`](../shared_minecraft). Both are shaded into each Fabric jar -- see
those READMEs for feature detail.

## Build

From the repo root:

    pwsh scripts/build-fabric.ps1            # all targets -> dist/
    pwsh scripts/build-fabric.ps1 26.2       # one target

Or directly:

    cd Fabric
    ./gradlew build -PmcVersion=26.2 -PfabricApiVersion=0.152.1+26.2

| MC target | minecraft | fabric-api | declared compat |
|-----------|-----------|------------|-----------------|
| 26.1 | 26.1.2 | 0.150.0+26.1.2 | >=26.1- <26.2 |
| 26.2 | 26.2 | 0.152.1+26.2 | >=26.2- <26.3 |
| 26.3 | 26.3-snapshot-1 | 0.153.1+26.3 | >=26.3- <26.4 |

Toolchain: relativitymc neo-loom 1.16.0-alpha.4, fabric-loader 0.19.3, Java 25.
To add a new 26.x version, add one row to the matrix in `scripts/build-fabric.ps1`.
