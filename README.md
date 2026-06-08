# Chunksmith

Chunksmith is a Minecraft chunk pre-generator that **paces generation to keep your server healthy** — it builds large areas ahead of time without saturating your disk or freezing the server.

It is a fork of [Chunky](https://github.com/pop4959/Chunky) by **pop4959**, licensed under **GPL-3.0**, and maintained by **Kishku7**.

## Why Chunksmith?

Chunky pre-generates chunks well, but on slower hardware a heavy pre-gen can saturate disk I/O — leaving the server unresponsive to `pause`/`stop`, and on an empty server even sending it to sleep mid-run. Chunksmith focuses on generating **safely under real-world conditions**:

- **Adaptive I/O throttle** — on Fabric, generation concurrency is steered by live server tick-health: it backs off when the server starts to fall behind and ramps back up as it recovers. A per-chunk latency backstop guards against disk stalls on every platform.
- **Stays awake during pre-gen** — while a generation task is running, the server won't `pause-when-empty` out from under an unattended pre-gen.
- **Conflict-safe** — if the original Chunky is also installed, Chunksmith disables it (Paper/Bukkit) or tells you to remove it (Fabric).
- **Drop-in migration** — on first run Chunksmith adopts your existing Chunky configuration automatically (see below), so upgrading is seamless.
- Ongoing focus on generation bug-fixes, with standalone *generate-and-store* on the roadmap.

## Supported platforms

Primary targets are **Fabric** and **PaperMC** (Minecraft 26.1.x, Java 25). Bukkit/Spigot/Folia and Forge/NeoForge/Sponge build from the same codebase. Paper-specific enhancements are used when Paper is detected; Spigot and Velocity-backend setups remain supported.

## Install

- **Fabric:** place the jar in `mods/` (requires Fabric API).
- **Paper / Spigot / Bukkit / Folia:** place the jar in `plugins/`.

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
| `throttle-target-mspt` | `150` | Target ms/tick the throttle steers toward (Fabric tick-health signal) |
| `throttle-max-chunk-millis` | `750` | Per-chunk latency backstop — back off if a single chunk load exceeds this |

Defaults are tuned to keep the server responsive on modest hardware.

## Credits & license

Original **Chunky** by pop4959 and contributors. **Chunksmith** fork maintained by Kishku7.
Licensed under the **GNU General Public License v3.0** — see [`LICENSE`](LICENSE).
