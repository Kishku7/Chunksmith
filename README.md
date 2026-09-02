# Chunksmith - Build Guide

`CSv3-Current` is the Chunksmith source tree for the 3.x line and the branch all current work
happens on. One codebase builds every supported target - the Fabric, Forge and NeoForge mods plus
the Bukkit/Paper plugin - across Minecraft 1.20.1 through 26.3.

**This branch is the build guide.** For what Chunksmith is and how to run it, see the
[landing page](https://github.com/Kishku7/Chunksmith); for every setting, command and how-to, see the
**[wiki](https://github.com/Kishku7/Chunksmith/wiki)**. Bug reports and questions go to
[mod_support](https://github.com/Kishku7/mod_support/issues).

Other branches: `main` is the landing README only (it is also the source of the Modrinth
description). `CSv3_old` is the superseded 3.x line, kept for reference. `CSv2_archive` is the
frozen 2.x line.

## What you need installed

Chunksmith builds on Windows using PowerShell build scripts and per-cell Gradle wrappers.

**Required:**

- **Windows + PowerShell 7 (`pwsh`)** - the build scripts are `.ps1` and call `gradlew.bat`.
- **Python 3 with Cog (`cogapp`) on `PATH`:**

      pip install cogapp

  Cog is the code generator that resolves cross-version API drift, and the build scripts invoke it
  automatically - which means it has to be installed before you build. Its brain is
  `_codegen/compat.py`: pure Python, in-repo.
- **JDKs, installed and discoverable by Gradle's toolchain detection.** There is **no foojay
  auto-download** configured, so you must install these yourself:
    - JDK 17 (Temurin/Adoptium)
    - JDK 21
    - JDK 25

  Which JDK each target uses is in the matrix below. Install all three to build the whole tree, or
  just the one(s) for the cells you care about.

**Provided for you (do NOT install manually):**

- **Gradle** - each cell ships a wrapper (`gradlew.bat`). Pre-26 cells use Gradle 8.14; the 26 cells
  and all plugin cells use Gradle 9.4.1.
- **Loader SDKs and dependencies** - Gradle downloads them on first build: Fabric Loom + Fabric API,
  NeoForge ModDevGradle, Forge (ForgeGradle 6), and the Paper API for the plugin. You need to be
  online the first time each cell is built.

## How to build

From the repo root, run the loader script for what you want. With no argument it builds every cell
for that loader into `dist/`; pass a version to build a single cell.

    pwsh scripts/build-fabric.ps1             # all Fabric cells
    pwsh scripts/build-fabric.ps1 26.2        # one target
    pwsh scripts/build-neoforge.ps1           # all NeoForge cells
    pwsh scripts/build-forge.ps1              # all Forge cells
    pwsh scripts/build-plugin.ps1             # plugin: 1.20.x / 1.21.x / 26.x
    pwsh scripts/build-plugin.ps1 -Only 26.x  # one plugin line

All jars land in `dist/`. The pre-26 mod cells run Cog code-generation automatically
(`scripts/cog-gen.ps1`) before compiling; the unified 26 cells build from a `-P` version matrix
supplied by the build script.

Everything compiles with `-Xlint:all` and is expected to build with **zero warnings**. Note that
Gradle reports `BUILD SUCCESSFUL` with warnings present and they scroll past mid-log, so a build is
only green once the log has actually been grepped for `warning:` - on **both** stdout and stderr.

## Toolchain matrix

| Loader   | MC versions                                               | JDK | Gradle |
|----------|-----------------------------------------------------------|-----|--------|
| Fabric   | 1.20.1, 1.20.4                                            | 17  | 8.14   |
| Fabric   | 1.20.6, 1.21.1, 1.21.4, 1.21.5, 1.21.8, 1.21.10, 1.21.11 | 21  | 8.14   |
| Fabric   | 26 (26.1 / 26.2 / 26.3)                                  | 25  | 9.4.1  |
| NeoForge | 1.20.6, 1.21.1, 1.21.4, 1.21.8, 1.21.10, 1.21.11         | 21  | 8.14   |
| NeoForge | 26 (26.1 / 26.2)                                         | 25  | 9.4.1  |
| Forge    | 1.20.1, 1.20.4                                            | 17  | 8.14   |
| Forge    | 1.20.6, 1.21.1, 1.21.4, 1.21.5, 1.21.8, 1.21.10, 1.21.11 | 21  | 8.14   |
| Plugin   | 1.20.x, 1.21.x                                           | 21  | 9.4.1  |
| Plugin   | 26.x                                                     | 25  | 9.4.1  |

Notes:

- **Forge** stops at 1.21.11 - ForgeGradle 6 is the ceiling, and there is no Forge for MC 26.
- **NeoForge** begins at 1.20.6. NeoForge 1.20.1 is a fork of Forge and runs the Forge build; it is
  tested on a NeoForge 1.20.1 harness each release rather than assumed.
- **MC 26.3** is Fabric-only - NeoForge has not shipped a 26.3 line.

### Prerelease MC lines pin exactly

The 26.3 cell targets **one** prerelease build at a time (currently `26.3-pre-1`) and declares it
exclusively, because every 26.3 build so far has moved the resource `pack_format` - and a jar carries
exactly one, so it is wrong on every other 26.3 build. Two things there are worth knowing before you
bump it:

- **Read `pack_format` out of the build's own `resources/version.json`.** Do not extrapolate it. The
  snapshots moved it by one each time (89..95) and then `26.3-pre-1` jumped straight to **97**.
- **Read the Fabric-normalized id off a boot, not off the pattern.** For 26.x naming Fabric swaps
  only the last hyphen for a dot: `26.3-snapshot-7` becomes `26.3-alpha.7` and `26.3-pre-1` becomes
  **`26.3-pre.1`**. It is not `beta.1`. A wrong predicate still builds green and passes every static
  check; the server simply refuses to load the mod.

Both values live in the 26.3 row of `scripts/build-fabric.ps1`.

## Repository layout

| Directory | What it is |
|-----------|------------|
| `shared_common/` | MC-agnostic core - commands, tasks, shapes, trim, config, the API, region NBT, and the CSLOD format. Shared by the mod AND the plugin, and where the unit tests live. |
| `Fabric/`, `NeoForge/`, `Forge/` | Per-loader builds; one `<version>` subfolder per MC cell, plus the unified `26/` cell (Fabric and NeoForge). |
| `Plugin/` | Bukkit/Paper plugin - one jar per line (1.20.x / 1.21.x / 26.x) built over `shared_common`, with the shared plugin code in `Plugin/shared_plugin/`. |
| `_codegen/` | Cog generator: `compat.py` (version/era rules) + `cog_sources/` (the shared mod sources, and the Cog-instrumented drift files). |
| `scripts/` | Build scripts (`build-<loader>.ps1`), `cog-gen.ps1`, `prep-libs.py`, the release stager, and the verification helpers (`verify-platform.py`, `verify-pause-drain.py`, `_metadata.py`). |
| `libs/` | Staging for optional soft-dependency jars (gitignored) - see below. |
| `docs/` | Internal development notes (gitignored). |
| `dist/` | Build output (generated). |
| `publish.json` | Per-mod input for the shared Modrinth publisher. |

## How the code generation works

Cross-version API drift is resolved at build time by Cog, driven by `_codegen/compat.py`.
For each pre-26 mod cell, `scripts/cog-gen.ps1`:

1. Copies `_codegen/cog_sources/shared/` into `<Cell>/gen/` verbatim.
2. Overwrites the drifting files with the Cog-instrumented copies from `_codegen/cog_sources/`.
3. Adds or removes the presence-gated accessors for that MC version.
4. Runs `cog` to resolve the version define.
5. Regenerates `chunksmith.mixins.json` to match the files actually present.

`_codegen/cog_sources/` is the single source of truth for the shared mod layer - there is no separate
`shared_minecraft/` tree, and `<Cell>/gen/` is a build artifact that is never committed. The cell's
Gradle build compiles `<Cell>/gen/`, which is why Cog must be installed before building. Cog-gen
never touches the unified 26 cells; those build from a `-P` version matrix supplied by the build
script.

## Optional soft dependencies (voxy / Distant Horizons)

Neither renderer is bundled. Voxy is All-Rights-Reserved and Distant Horizons is LGPL; both are
optional soft dependencies, compiled against and never shipped.

- **Distant Horizons** is compiled against its published API artifact
  (`maven.modrinth:distanthorizonsapi`), so Gradle fetches it and there is nothing to stage.
- **voxy** has no published API artifact. Its jars go in the repo-root `libs/` (gitignored) - run
  `python scripts/prep-libs.py` to stage them.

Which cells compile LOD support at all is decided by `_codegen/compat.py` (`has_lod` / `has_dh` /
`has_voxy`). Those gates are about the **renderer**, which is client-side: they say where a
Chunksmith client can draw LOD, not where a server can produce or send it.

## Platform notes

- **Folia is no longer a supported or tested platform.** Plugin testing is on Paper. Existing jars
  that declare `folia-supported` keep working for now; the flag and the runtime branches come out in
  a later release.
- **The plugin has no in-band fallback.** When the LOD backchannel port cannot be bound or reached,
  the mod drips the same bytes down the game connection; the plugin does not, so a blocked port means
  players get nothing rather than something slow. It is logged plainly. This is the one real
  behavioural gap against the mod.
- **A Bukkit server silently drops a plugin message on a channel the client never announced** via
  `minecraft:register` - no exception, no log, no packet. A modern Fabric client does not send that
  announcement, so the plugin registers the outgoing channel for any player who has already spoken to
  it on that channel. Without that step the server hears the client perfectly and the client hears
  nothing back, which is indistinguishable from working on the server side.

## Credits / License

Original Chunky by pop4959; the Paper chunk-system internals referenced in the code are Moonrise
(Spottedleaf). Chunksmith is maintained by Kishku7. GPL-3.0-only.
