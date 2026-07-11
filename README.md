# Chunksmith - Build Guide (minecraft-1.20-26.3 branch)

This branch is the unified Chunksmith source tree. One codebase builds every supported
target - the Fabric, Forge, and NeoForge mods plus the Bukkit/Paper/Folia plugin - across Minecraft
1.20.1 through 26.3.

For what Chunksmith is and how to use it, see the [landing page](https://github.com/Kishku7/Chunksmith).
Questions or bug reports: https://github.com/Kishku7/mod_support/issues

## What you need installed

Chunksmith builds on Windows using PowerShell build scripts and per-cell Gradle wrappers.

**Required:**

- **Windows + PowerShell 7 (`pwsh`)** - the build scripts are `.ps1` and call `gradlew.bat`.
- **Python 3 with Cog (`cogapp`) on `PATH`:**

      pip install cogapp

  Cog is the code generator that resolves cross-version API drift. It is invoked
  automatically by the build scripts, so it must be installed before you build. The
  generator brain is `_codegen/compat.py` (pure Python, in-repo).
- **JDKs, installed and discoverable by Gradle's toolchain detection.** There is **no
  foojay auto-download** configured, so you must install these yourself:
    - JDK 17 (Temurin/Adoptium)
    - JDK 21
    - JDK 25

  Which JDK each target uses is in the matrix below. Install all three to build the whole
  tree, or just the one(s) for the cells you care about.

**Provided for you (do NOT install manually):**

- **Gradle** - each cell ships a wrapper (`gradlew.bat`). Pre-26 cells use Gradle 8.14;
  the 26 cells and all plugin cells use Gradle 9.4.1.
- **Loader SDKs and dependencies** - Gradle downloads them on first build: Fabric Loom +
  Fabric API, NeoForge ModDevGradle, Forge (ForgeGradle 6), and the Paper/Folia API for
  the plugin. An internet connection is required the first time each cell is built.

## How to build

From the repo root, run the loader script for what you want. With no argument it builds
every cell for that loader into `dist/`; pass a version to build a single cell.

    pwsh scripts/build-fabric.ps1             # all Fabric cells (1.20.1..1.21.11 + 26.1/26.2/26.3)
    pwsh scripts/build-fabric.ps1 26.2        # one target
    pwsh scripts/build-neoforge.ps1           # all NeoForge cells (1.20.6..1.21.11 + 26.1/26.2)
    pwsh scripts/build-forge.ps1              # all Forge cells (1.20.1..1.21.11; no 26 - FG6 ceiling)
    pwsh scripts/build-plugin.ps1             # plugin: 1.20.x / 1.21.x / 26.x
    pwsh scripts/build-plugin.ps1 -Only 26.x  # one plugin line

All jars land in `dist/`. The pre-26 mod cells run Cog code-generation automatically
(`scripts/cog-gen.ps1`) before compiling; the unified 26 cells build from a `-P` version
matrix.

## Toolchain matrix

| Loader   | MC versions                                                | JDK | Gradle |
|----------|------------------------------------------------------------|-----|--------|
| Fabric   | 1.20.1, 1.20.4                                             | 17  | 8.14   |
| Fabric   | 1.20.6, 1.21.1, 1.21.4, 1.21.5, 1.21.8, 1.21.10, 1.21.11  | 21  | 8.14   |
| Fabric   | 26 (26.1 / 26.2 / 26.3)                                   | 25  | 9.4.1  |
| NeoForge | 1.20.6, 1.21.1, 1.21.4, 1.21.8, 1.21.10, 1.21.11          | 21  | 8.14   |
| NeoForge | 26 (26.1 / 26.2)                                          | 25  | 9.4.1  |
| Forge    | 1.20.1, 1.20.4                                             | 17  | 8.14   |
| Forge    | 1.20.6, 1.21.1, 1.21.4, 1.21.5, 1.21.8, 1.21.10, 1.21.11  | 21  | 8.14   |
| Plugin   | 1.20.x, 1.21.x                                            | 21  | 9.4.1  |
| Plugin   | 26.x                                                      | 25  | 9.4.1  |

Notes:

- **Forge** stops at 1.21.11 - ForgeGradle 6 is the ceiling and there is no Forge for MC 26.
- **NeoForge** begins at 1.20.6; the 1.20.1 fork point is covered by the Forge jar (NeoForge
  1.20.1 runs the Forge build).
- **MC 26.3** has a Fabric build but no NeoForge yet (NeoForge has not shipped for 26.3).

## Repository layout

| Directory | What it is |
|-----------|------------|
| `shared_common/`    | MC-agnostic core - commands, tasks, shapes, trim, config, the API, region NBT. Shared by the mod AND the plugin. |
| `shared_minecraft/` | Shared mod layer - the Mixins/accessors used by the Fabric and NeoForge builds. Cog copies this into `<Cell>/gen/` per cell at build time. |
| `Fabric/`, `NeoForge/`, `Forge/` | Per-loader builds; one `<version>` subfolder per MC cell, plus the unified `26/` cell (Fabric and NeoForge). |
| `Plugin/`           | Bukkit/Paper/Folia plugin - one jar per line (1.20.x / 1.21.x / 26.x) built over `shared_common`. |
| `_codegen/`         | Cog generator: `compat.py` (version/era rules) + `cog_sources/` (instrumented drift files). |
| `scripts/`          | The build scripts (`build-<loader>.ps1`) and `cog-gen.ps1`. |
| `dist/`             | Build output (generated). |

## How the code generation works

Cross-version API drift is resolved at build time by Cog, driven by `_codegen/compat.py`.
For each pre-26 mod cell, `scripts/cog-gen.ps1`:

1. Copies `shared_minecraft` into `<Cell>/gen/`.
2. Swaps in the Cog-instrumented drift files from `_codegen/cog_sources/`.
3. Adds or removes the presence-gated accessors for that MC version.
4. Runs `cog` to resolve the version define.
5. Regenerates `chunksmith.mixins.json` to match the files actually present.

The cell's Gradle build compiles `<Cell>/gen/`, not `shared_minecraft` directly - which is
why Cog must be installed before building. The unified 26 cells do not use cog-gen; they
build from a `-P` version matrix supplied by the build script.

## LOD generation (26.x Fabric, in development on `dev`)

Chunksmith can emit **level-of-detail data while it pregenerates** - so the same pass that builds your
world also builds the LODs for it. No second scan, no re-reading region files, no separate LOD pregen.

The point is that the LOD data is written in **Chunksmith's own neutral format (CSLOD)** rather than in
any one LOD mod's private shape. From that single store we can serve **every** LOD consumer:

| Consumer | How it is fed |
|----------|---------------|
| [Voxy](https://modrinth.com/mod/voxy) | Fed live during pregen, **and** replayable afterwards (`/cslod inject`) |
| [Distant Horizons](https://modrinth.com/mod/distanthorizons) | Chunksmith registers as DH's world-generator override and answers straight from the store |
| Remote clients | Planned - the same bytes are already the wire format |

### Why a neutral format

- **Voxy's on-disk format is not frozen** (`STORAGE_VERSION = 0`, with an unused key re-order sitting
  in the code), and its block/biome ids are **database-local and allocation-ordered** - issued lazily
  by a live mapper, so they cannot even be computed offline. Anything written in voxy's shape is
  hostage to voxy's internals.
- **Distant Horizons is a completely different data model** - run-length columns in SQLite, not a dense
  voxel grid - and it *pulls* data rather than accepting pushes.

CSLOD stores **vanilla registry strings**: full block *states* (`minecraft:oak_stairs[facing=east,
waterlogged=true]`), per-voxel biomes, and sky/block light kept **separate**, carried even for air, all
the way to the build ceiling. That is the union of what both mods need, so both can be reconstructed
losslessly - and DH's own wrapper factory eats our palette strings verbatim, with no id translation at
either end.

### What it costs

Measured on MC 26.1.2, a 1089-chunk pregen:

| | |
|---|---|
| CSLOD store | **~5.8 KB per chunk** |
| Voxy's RocksDB, same chunks | ~43 KB per chunk (**7.4x larger**) |
| Pregen slowdown with the store on | **~16%** |
| Compression | JDK Deflate - **zero native dependencies** |

The store is plain Anvil-style region files: no native database, no lock, readable by a second process
while the game runs. Writes append the payload and *then* update the index, so a torn write costs one
chunk, never the file.

### The trick worth stealing

**You do not need the LOD mod installed when you pregenerate.** Pregen a world today with nothing but
Chunksmith; install Voxy or Distant Horizons a month later; run `/cslod inject` (Voxy) or just load the
world (DH) - and the LODs are there, instantly, with no regeneration. The world does not have to be
touched again.

### Usage

Off by default. In `config/chunksmith.json`:

    "lodEnabled": true,        // write the CSLOD store during pregen (and feed voxy if installed)
    "lodDhOverride": true      // additionally serve Distant Horizons from the store

Commands (op):

    /cslod status              // store path, size, and whether voxy / DH are being served
    /cslod inject              // replay the whole store into voxy

Notes:

- Voxy pins an exact Sodium version (`<= 0.8.12` on 26.1.2) and will not load without it.
- `lodDhOverride` **replaces** DH's own distant generator for that level: pregenerated area appears
  instantly, everything else returns empty. That is right for a world you have pregenerated and wrong
  for one you have not - which is why it is opt-in.
- Neither mod is bundled. Voxy is All-Rights-Reserved and DH is LGPL; both are optional soft
  dependencies, compiled against and never shipped. To build the LOD code, drop the jars in
  `Fabric/26/libs/` (gitignored).

## Credits / License

Original Chunky by pop4959; the Paper/Folia chunk-system internals referenced in the code
are Moonrise (Spottedleaf). Chunksmith is maintained by Kishku7. GPL-3.0-only.
