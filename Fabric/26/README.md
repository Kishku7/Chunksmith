# Chunksmith - Fabric (Minecraft 26.1 - 26.3)

The Fabric mod build. One version-agnostic source builds every 26.x target; the only
per-version differences are the `minecraft` + `fabric-api` dependency versions and the
`fabric.mod.json` compatibility range. The compiled code is identical across versions
(mojmap-native, no remap).

Shared code: the MC-agnostic core is [`../shared_common`](../shared_common); the
Minecraft-touching mod layer (Mixins and accessors that keep big pregens safe on vanilla) is
generated per cell into `gen/` from [`../../_codegen/cog_sources`](../../_codegen/cog_sources),
which is the single source of truth for it. Both are shaded into each jar.

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
| 26.3 | 26.3-pre-1 | 0.159.1+26.3 | 26.3-pre.1 (exact) |

**`scripts/build-fabric.ps1` is the canonical matrix** -- this table mirrors it, and the
defaults in `build.gradle.kts` / `gradle.properties` exist only so a bare `gradlew` runs.
When the 26.3 pin moves, all three change together or they disagree.

26.3 is pinned to ONE prerelease build and declared exactly, because every 26.3 build so far
has moved the resource `pack_format` and a jar carries only one. Read `pack_format` out of the
target's own `resources/version.json` (26.3-pre-1 is **97** -- it jumped two from snapshot-7's
95), and read the Fabric-normalized dep off a boot: `26.3-pre-1` normalizes to `26.3-pre.1`,
not `beta.1`.

Toolchain: relativitymc neo-loom 1.16.0-alpha.4, fabric-loader 0.19.3, Java 25.
To add a new 26.x version, add one row to the matrix in `scripts/build-fabric.ps1`.
