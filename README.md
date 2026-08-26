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
| Remote clients | The store is streamed over the wire and feeds the player's own voxy / DH. **Chunksmith does this itself** as of 3.1.0 - install the same jar on the client and it is both halves. (This used to need a separate **Chunksmith-Client** mod; that mod is **discontinued** and is declared incompatible with 3.1.0+, since both would register the same chunksmith:lod channel.) |

LOD ships on the cells where a renderer actually exists: Fabric 1.20.1 / 1.21.1 / 1.21.11 / 26.x,
NeoForge 1.21.1 / 1.21.11 / 26.1 / 26.2, Forge 1.20.1 (DH everywhere on that list, needs >= 2.3.0-b;
voxy only on Fabric 1.21.11 + 26.x). The Bukkit/Paper/Folia plugin GENERATES a CSLOD store
(`CsLodExtractor`, `LodSupport`) but cannot SERVE it: it registers no plugin-messaging channel and
has no `CsLodHttpServer`, no `ServerHello` and no tokens, so a client connected to a plugin server
never learns there is anything to fetch. Server-side generation only, deliberately, as a later
phase (mod_support #18 was a user hitting exactly this). Gates: `_codegen/compat.py` (`has_lod` /
`has_dh` / `has_voxy`).

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

`lodEnabled` is a TRISTATE, default `auto`. In `config/chunksmith/config.json`:

    "lodEnabled": "auto",      // ON if a renderer (distanthorizons / voxy / a voxy fork) is loaded,
                               //   ON on a dedicated server (its store is what remote clients pull),
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

### The LOD backchannel port

On a multiplayer server the client pulls LOD region files over a small read-only HTTP listener rather
than through the game connection, because pushing hundreds of megabytes down the play channel starves
the game loop. That listener needs a port.

By default it is **game port + 1** (25565 -> 25566), which needs no configuration and is right on a
machine you control. It is wrong on a managed host, which rents you a fixed set of ports and has no
reason to give you the one next to your game port. Set it explicitly:

    "lodBackchannelPort": 30000   // 0 (default) = derive it, game port + 1

or from in-game, with immediate effect and no restart:

    /cs set lodBackchannelPort 30000
    /cs set lodBackchannelPort 0       // back to the derived port

**Clients need no matching setting, ever.** The server tells each client which port to use when they
connect, so a player moving between servers picks up whatever each one is running. There is no client
port option and there never has been - the client is already connected to the host, so only the port
was ever in question, and that is negotiated.

Changing it live stops the old listener, binds the new one, and re-issues every connected client a
download token in the same pass. Nobody has to relog.

Rules worth knowing:

- The port must be **1024-65535**, and must not be the game's own port. Anything else is refused and
  logged with the reason, rather than clamped to a number you did not ask for.
- If the port cannot be bound, Chunksmith **keeps working** - it falls back to the in-band channel,
  which is correct but much slower - and says so at WARN, naming the port. If LODs are not arriving on
  a server that has Chunksmith on both sides, that log line is the first place to look.
- `/cs status` reports the port in force and whether it was derived or configured.
- Open the port to your players. A backchannel that binds fine but is firewalled looks identical to
  one that never bound, from the player's side: the client probes it once, warns, and falls back.

## Letting other mods build on freshly generated land

A pregen normally drops a chunk's ticket the moment the chunk is generated. That is what keeps a run's
memory flat, and for pure terrain it is exactly right.

It is wrong as soon as another mod is listening. Mods that add their own structures often react to
"a new chunk appeared" and then do the actual work on a later server tick, checking first that the
footprint they want to build on is still loaded. Against a pregen that released each chunk instantly,
that check never passed: the chunk and its neighbours were already gone, so the mod deferred, and it
deferred for the entire run. Measured with Millenaire on a NeoForge 1.21.1 pregen before this was
fixed: **309 placement attempts, 309 deferrals, zero villages** (mod_support #14).

Chunksmith now holds each generated chunk until **all eight of its neighbours have been generated
too**, plus a short delay, and then releases it. What is held at any moment is the frontier of the
sweep and nothing else, so the cost is bounded by the shape of the pattern rather than by the size of
the run, and it falls to zero as the run finishes.

The rule is about chunks, not about any one mod -- Chunksmith contains no mod-specific compatibility
code, and anything that builds on newly generated chunks benefits from it.

All three live in `config/chunksmith/config.json`, and like every other setting they can be read and
changed in-game with `/cs set` (see [Settings](#settings)) instead of editing the file:

```jsonc
"pregenSettle": true,            // hold each chunk until its neighbours exist (default)
"pregenSettleDelayTicks": 40,    // and for this long afterwards -- two seconds
"pregenSettleRadius": 7          // chunks of ground loaded together by the trailing sweep
```

**When to turn it off.** `"pregenSettle": false` restores the old behaviour exactly: the ticket is
released inline, nothing is allocated, and there is no bookkeeping at all. That is the right setting
if you are pregenerating pure terrain with no mods that build on new chunks, because holding the
frontier costs memory proportional to the width of the sweep and gives up a little throughput for
something nothing is going to use. If you are running a modpack, leave it on.

**When to raise the delay.** If a mod's structures still fail to appear, it is probably acting more
than two seconds after the chunk arrives -- its own queue may be backed up. Raise
`pregenSettleDelayTicks` (20 ticks = 1 second, maximum 600). Higher values hold more chunks for
longer, so raise it deliberately rather than by default.

**When to raise the radius.** `pregenSettleRadius` (default 7 chunks, maximum 16) is how much ground
the trailing sweep loads together at one stop. It is sized to the largest footprint a mod is likely
to want at once: a Millenaire village reaches about 90 blocks, which is six chunks, so seven leaves
room. A mod that places something bigger than that may still find the far edge of its own structure
unloaded -- raise the radius for it. Each increment costs memory per stop and more disk reads, so
this is the last dial to touch, not the first.

**Note on upgrading.** Chunksmith never rewrites a config that already exists, so these three keys
will not appear in a config written by an earlier version. They still take their defaults and the
feature is fully on. `/cs set` reads the values in force rather than the file, so it shows them
correctly either way -- and setting any one of them writes the file, keys and all.

## Settings

Every setting in `config/chunksmith/config.json` can be read and changed from the server console or
in-game, without editing the file and without a restart:

    /cs set                          // list every setting and the value in force
    /cs set <name>                   // show one
    /cs set <name> <value>           // change it, and save it

Two things worth knowing. Settings that have a legal range are **clamped as they are written**, so
`/cs set` reports the value that is actually in force rather than echoing what you typed -- if you
ask for a radius of 40 you will be told it is 16. And a value that cannot be understood at all (a
word where a number belongs, a language that is not shipped) is refused outright rather than
silently becoming a default.

`/cs silent` and `/cs quiet` still work; they are the same two settings under their old names.

### Throughput: `dispatchMaxConcurrent`

How many chunk requests Chunksmith keeps in flight at once. This is the pipeline's WIDTH, and on a
healthy server it is the setting that actually decides the rate.

A chunk request spends almost all of its life WAITING. Vanilla walks it up through its generation
statuses at roughly one hop per tick, so the wall-clock latency of a single chunk is over a second
even on a completely idle machine. You do not make that faster; you run more of them at once.

    "dispatchMaxConcurrent": 200,    // default: min(400, max(50, cpu_cores * 25))

Measured on an 8-core dedicated server:

| value | chunks/sec | resident chunks | heap |
|---|---|---|---|
| 50 (the old fixed cap) | 31.6 | ~3,000 | 20% |
| **200** | **43.9** | 2,390 | 33% |
| 600 | 42.4 | 4,952 | 34% |

200 is the knee. Above it you buy nothing, because the remaining ceiling is vanilla promoting
roughly 2.2 chunks per tick at 20 TPS -- about 43 chunks/sec -- and no amount of width beats that.
Below it you leave throughput on the table: at the old cap of 50 nothing on the box was saturated
(CPU 255 of 800 percent, worker threads ~24 percent busy, the server thread ~10) and Chunksmith was
spending 0.2ms of a 25ms tick allowance. The cap was the only thing in the way.

The cost of raising it is memory, roughly linearly -- more chunks resident at once -- which is what
`throttleMaxHeapPercent` and the residency gate are there to catch. Lower it on a memory-tight box;
raise it where you have cores to spare. The default scales with the machine because the knee is
per-core: a fixed 200 would be as wrong on a 2-core VPS as the old fixed 50 was on 8 cores.

The older `-Dchunksmith.maxWorkingCount` system property still wins when set, so an existing launch
script keeps working.

### Protecting the server: the tick budget

    "throttleTickBudgetMillis": 25,      // tick time we may ADD before backing off
    "throttlePlayerReserveMillis": 20,   // ...minus this much per online player
    "throttleCeilingMillis": 150,        // absolute stop, whoever is to blame (~6.7 TPS)
    "throttleMaxHeapPercent": 85,        // hold dispatch above this much of -Xmx. 0 disables
    "throttleMaxAddedChunks": 0,         // hard cap on chunks we add to memory. 0 disables

Chunksmith measures what the server costs WITHOUT it and what it adds ON TOP, rather than steering
on absolute tick time. That distinction matters: a server already running at 75ms for its own
reasons would otherwise make Chunksmith throttle itself to nothing for load it did not cause.

Each online player also SHRINKS the allowance by `throttlePlayerReserveMillis`. An empty server gets
the full budget; a server with people on it actively gives ground rather than merely not making
things worse.

`throttleCeilingMillis` is the one bound that is deliberately ABSOLUTE. Every other limit here is
relative to a measured baseline, and a relative bound moves with the thing it is supposed to protect
against -- past the ceiling the server is unplayable no matter whose fault it is, so the run yields.

### Yielding entirely: `autoPauseOnOverload`

    "autoPauseOnOverload": true,      // pause the run when the server stays overloaded
    "autoPauseGraceSeconds": 120,     // ...for this long, and resume when it recovers

Backing off is not always enough. Under sustained load the run pauses itself and resumes when the
server is healthy again, so a pregen left running overnight cannot sit on a struggling server.

### `pregenSettleMaxHeld`

    "pregenSettleMaxHeld": 256,       // hard cap on the settle frontier, counted in TICKETS

Counted in tickets, but paid for in tickets TIMES their halo: a FULL chunk drags in the 17x17 ring
of worldgen context around it, so each held ticket is worth roughly 25 resident chunks. That is why
the cap is small -- it is not the number you feel, the product is.


On Paper and Folia the three `pregenSettle*` settings report that they do not apply. Bukkit does not
manage chunk tickets, so there is no window for Chunksmith to hold open there.

## Credits / License

Original Chunky by pop4959; the Paper/Folia chunk-system internals referenced in the code
are Moonrise (Spottedleaf). Chunksmith is maintained by Kishku7. GPL-3.0-only.
