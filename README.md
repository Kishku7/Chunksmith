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

## Credits / License

Original Chunky by pop4959; the Paper/Folia chunk-system internals referenced in the code
are Moonrise (Spottedleaf). Chunksmith is maintained by Kishku7. GPL-3.0-only.
