# Chunksmith - Fabric (Minecraft 26.1 - 26.3)

One version-agnostic Fabric source builds every supported 26.x Minecraft version.
The only per-version differences are the `minecraft` + `fabric-api` dependency versions
and the `fabric.mod.json` compatibility range -- the compiled code is identical across
versions (mojmap-native, no remap).

## Build

From the repo root:

    pwsh build-all-fabric.ps1            # all targets -> dist/
    pwsh build-all-fabric.ps1 26.2       # one target

Or directly:

    cd Fabric
    ./gradlew build -PmcVersion=26.2 -PfabricApiVersion=0.152.1+26.2

Targets: 26.1 (mc 26.1.2), 26.2 (mc 26.2), 26.3 (mc 26.3-snapshot-1).
Toolchain: relativitymc neo-loom 1.16.0-alpha.4, fabric-loader 0.19.3, Java 25.

To add a new 26.x version, add one row to the matrix in `build-all-fabric.ps1`.