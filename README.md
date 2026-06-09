# Chunksmith

Chunksmith is a Minecraft chunk pre-generator that **paces generation to keep your server healthy** — it builds large areas ahead of time without saturating your disk or freezing the server.

It is a fork of [Chunky](https://github.com/pop4959/Chunky) by **pop4959**, licensed under **GPL-3.0**, and maintained by **Kishku7**.

## Why Chunksmith?

Chunky pre-generates chunks well, but on slower hardware a heavy pre-gen can saturate disk I/O — leaving the server unresponsive to `pause`/`stop`, and on an empty server even sending it to sleep mid-run. Chunksmith focuses on generating **safely under real-world conditions**:

- **Adaptive I/O throttle** — generation concurrency is steered by live server tick-health (Fabric, NeoForge, and Paper): it backs off when the server starts to fall behind and ramps back up as it recovers, with a per-chunk latency backstop guarding against disk stalls on every platform.
- **Run it 24/7 — even with players online.** Because generation is paced by real-time server tick-health, Chunksmith automatically yields capacity to players the moment the server gets busy and reclaims it as load eases. There's no need to schedule pre-generation for off-hours or empty the server first — leave it running continuously and it stays out of players' way until the job is finished.
- **Write-queue backpressure** — Chunksmith watches the deferred region-write backlog (chunk writes queued to disk but not yet flushed) and holds off *dispatch* the moment it exceeds a cap, resuming once it drains. Generation can no longer outrun your disk: the unflushed-write window stays bounded, so `pause`/`stop` stay instant and a crash can never strand a giant write queue. On Fabric/NeoForge this reads the live `IOWorker` queue directly; on Paper/Spigot it is detected reflectively.
- **Stays awake during pre-gen** — while a generation task is running, the server won't `pause-when-empty` out from under an unattended pre-gen.
- **Conflict-safe** — if the original Chunky is also installed, Chunksmith disables it (Paper/Bukkit) or tells you to remove it (Fabric).
- **Drop-in migration** — on first run Chunksmith adopts your existing Chunky configuration automatically (see below), so upgrading is seamless.
- Ongoing focus on generation bug-fixes, with standalone *generate-and-store* on the roadmap.

## Supported platforms

Primary targets are **Fabric** and **PaperMC** (Minecraft 26.1.x, Java 25). Bukkit/Spigot/Folia and Forge/NeoForge/Sponge build from the same codebase. Paper-specific enhancements are used when Paper is detected; Spigot and Velocity-backend setups remain supported.

## Download & install

Compiled jars are on the [**Releases**](https://github.com/Kishku7/Chunksmith/releases/latest) page — no building required:

- **Plugin** (Paper / Spigot / Bukkit / Folia) — download `Chunksmith-Plugin-<version>.jar` and drop it in your server's `plugins/` folder.
- **Mod** (Fabric) — download `Chunksmith-Mod-Fabric-<version>.jar` and drop it in `mods/` (requires Fabric API). *A NeoForge build is in testing and will be added to Releases once verified.*

Also published on [Modrinth](https://modrinth.com/mod/chunksmith).

Remove any existing **Chunky** jar — Chunksmith supersedes it. (On Paper/Bukkit, Chunksmith will disable Chunky automatically and ask you to delete it.)

## Usage

The command is **`/cs`** (with **`/chunksmith`** as a full-name alias). The legacy **`/chunky`** and **`/cy`** still work but print a deprecation notice pointing you to `/cs`.

```
/cs start          # pre-generate the current/selected area
/cs pause          # pause (resumable)
/cs continue       # resume a paused task
/cs progress       # show rate / ETA / throttle status
```

## Configuration & migration

On first run, if you don't already have a Chunksmith config, the mod **adopts your existing Chunky config in place** — `config/chunky` → `config/chunksmith` on Fabric, `plugins/Chunky` → `plugins/Chunksmith` on Bukkit/Paper. If a Chunksmith config already exists, the old Chunky directory is left untouched.

Throttle behaviour is tunable:

| Key | Default | Meaning |
|-----|---------|---------|
| `io-throttle` | `true` | Enable adaptive throttling |
| `throttle-target-mspt` | `150` | Target ms/tick the throttle steers toward (Fabric/NeoForge/Paper tick-health signal) |
| `throttle-max-chunk-millis` | `750` | Per-chunk latency backstop — back off if a single chunk load exceeds this |
| `throttle-max-queued-writes` | `800` | Cap on queued (unflushed) chunk writes before generation is held off until the backlog drains (Fabric/NeoForge; `0` disables) |

Defaults are tuned to keep the server responsive on modest hardware.

## Real-world impact

Measured on a live Minecraft 26.1.x server pre-generating a multi-million-chunk overworld on a spinning-disk (HDD):

| | Stock Chunky | Chunksmith |
|---|---|---|
| Disk utilization during pre-gen | ~95–100% (pegged) | ~2–5% |
| `pause` responsiveness under load | seconds to minutes of lag | instant (same tick) |
| Post-pause disk drain | long, unbounded tail | bounded by the queue cap |
| Generation rate | choked by disk thrash | **higher** — ~65–78 chunks/s |

Same hardware, same world: Chunksmith generates **faster** while leaving the disk almost idle — because it stops feeding the disk faster than it can keep up, instead of burying it and choking on its own backlog.

## Credits & license

Original **Chunky** by pop4959 and contributors. **Chunksmith** fork maintained by Kishku7.
Licensed under the **GNU General Public License v3.0** — see [`LICENSE`](LICENSE).
