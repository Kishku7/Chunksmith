# Chunksmith - Build Guide (CSv3 branch)

`CSv3` is the unified Chunksmith source tree for the 3.x line - the branch all current work
happens on (renamed from `dev`, 2026-07-12). One codebase builds every supported
target - the Fabric, Forge, and NeoForge mods plus the Bukkit/Paper/Folia plugin - across Minecraft
1.20.1 through 26.3. The frozen 2.x line lives on `CSv2_archive` (formerly `minecraft-1.20-26.3`).

For what Chunksmith is and how to use it, see the [landing page](https://github.com/Kishku7/Chunksmith).

The landing-page README lives on `main` and IS the Modrinth description (`publish.py --sync-desc`
pushes it). The 3.0 LOD pitch went live there with the beta, so the old `readme-main_dev.md` staging
file has been deleted, per the convention: once the staging copy replaces `main`'s README, it goes.
Edit `main`'s `README.md` directly for user-facing wording.
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

## LOD generation (shipped in 3.0.0-beta-2)

Chunksmith can emit **level-of-detail data while it pregenerates** - so the same pass that builds your
world also builds the LODs for it. No second scan, no re-reading region files, no separate LOD pregen.

**A re-run fills LOD holes automatically (3.0.0-beta-3).** Already pregenerated a world before you
installed an LOD renderer? Just run the same pregen again. Chunksmith checks the CSLOD store as well as
the world, per chunk:

| On disk | What Chunksmith does |
|---------|----------------------|
| No chunk | Generate it - the LOD is built on the way past |
| Chunk, no LOD | **Load the chunk (no worldgen) and build the LOD from it** |
| Chunk + LOD | Skip entirely - no load, no write |

So the second run builds only what is missing, and a third run does nothing at all. Delete part of the
store and only those records come back. The presence check is a single 8 KB header read per region file,
so it costs nothing worth measuring. (`forceLoadExistingChunks: true` still means what it always did:
reprocess everything regardless. With LOD off, the skip behaviour is exactly as it was.)

The point is that the LOD data is written in **Chunksmith's own neutral format (CSLOD)** rather than in
any one LOD mod's private shape. From that single store we can serve **every** LOD consumer:

| Consumer | How it is fed |
|----------|---------------|
| [Voxy](https://modrinth.com/mod/voxy) | Fed live during pregen, **and** replayable afterwards (`/cslod inject`) |
| [Distant Horizons](https://modrinth.com/mod/distanthorizons) | Chunksmith registers as DH's world-generator override and answers straight from the store |
| Remote clients | [Chunksmith-Client](https://github.com/Kishku7/chunksmith-client) streams the store over the wire and feeds the player's own voxy / DH |

LOD ships on the cells where a renderer actually exists: Fabric 1.20.1 / 1.21.1 / 1.21.11 / 26.x,
NeoForge 1.21.1 / 1.21.11 / 26.1 / 26.2, Forge 1.20.1 (DH everywhere on that list, needs >= 2.3.0-b;
voxy only on Fabric 1.21.11 + 26.x). The Bukkit/Paper/Folia plugin has no LOD code at all - there is
no plugin-side renderer. Gates: `_codegen/compat.py` (`has_lod` / `has_dh` / `has_voxy`).

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

`lodEnabled` is a TRISTATE, default `auto`. In `config/chunksmith.json`:

    "lodEnabled": "auto",      // ON if a renderer (distanthorizons / voxy / a voxy fork) is loaded,
                               //   ON on a dedicated server (its store is what Chunksmith-Client pulls),
                               //   off otherwise. `true` / `false` force it and are never overridden.
    "lodDhOverride": true      // additionally serve Distant Horizons from the store

The resolution happens in `LodSupport.decide(Config, MinecraftServer)` and is logged, once, at server
start; `/cslod status` repeats it. A plain JSON boolean still parses (Gson coerces it to `"true"` /
`"false"`), so an existing config is never rewritten.

Commands (op):

    /cslod status              // store path, size, and whether voxy / DH are being served
    /cslod inject              // replay the whole store into voxy

Notes:

- Voxy pins an exact Sodium version (`<= 0.8.12` on 26.1.2) and will not load without it.
- `lodDhOverride` **replaces** DH's own distant generator for that level: pregenerated area appears
  instantly, everything else returns empty. That is right for a world you have pregenerated and wrong
  for one you have not - which is why it is opt-in.
- Neither mod is bundled. Voxy is All-Rights-Reserved and DH is LGPL; both are optional soft
  dependencies, compiled against and never shipped. DH is compiled against its published API
  artifact (`maven.modrinth:distanthorizonsapi`); the voxy soft-dep jars go in the repo-root
  `libs/` (gitignored) - run `python scripts/prep-libs.py` to stage them.

## Credits / License

Original Chunky by pop4959; the Paper/Folia chunk-system internals referenced in the code
are Moonrise (Spottedleaf). Chunksmith is maintained by Kishku7. GPL-3.0-only.
