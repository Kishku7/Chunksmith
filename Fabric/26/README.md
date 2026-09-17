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
| 26.3 | 26.3 | 0.160.6+26.3 | >=26.3- <26.4 |

**`scripts/build-fabric.ps1` is the canonical matrix** -- this table mirrors it, and the
defaults in `build.gradle.kts` / `gradle.properties` exist only so a bare `gradlew` runs.
When a pin moves, all three change together or they disagree.

**MC 26.3 went stable on 2026-09-15**, so it now takes the ordinary closed, prerelease-inclusive
range like every other line, at `pack_format` **97**. While it was in prerelease the cell had to
pin ONE build and declare it exactly, because every 26.3 build moved the resource `pack_format` and
a jar carries only one -- so the published jar did not load AT ALL on any other 26.3 build. Two
things from that era will apply again the moment a 26.4 prerelease opens:

- Read `pack_format` out of the target's own `resources/version.json`; never extrapolate it. The
  26.3 snapshots moved it by one each time (89..95) and then `26.3-pre-1` jumped straight to 97.
- Read the Fabric-normalized dep off a boot, not off the pattern: `26.3-snapshot-7` normalizes to
  `26.3-alpha.7` and `26.3-pre-1` to `26.3-pre.1`, not `beta.1`. A wrong predicate builds green.

Toolchain: relativitymc neo-loom 1.16.0-alpha.4, fabric-loader 0.19.3, Java 25.
To add a new 26.x version, add one row to the matrix in `scripts/build-fabric.ps1`.
