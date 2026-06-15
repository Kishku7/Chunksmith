# forge-1.21.1 — PLACEHOLDER (not yet implemented)

Dedicated **Forge** (LexForge) variant for **MC 1.21.1**. Needed because at 1.21.x
Forge and NeoForge are **separate, incompatible loaders** — unlike 1.20.1, one jar
cannot serve both. The existing `neoforge-1.21.1` variant is `net.neoforged.*` +
`neoforge.mods.toml` and will NOT load on Forge 1.21.1.

Status: **deferred** (staked out 2026-06-15; build deferred until the 1.20.x line
bugs are resolved). This folder is intentionally NOT registered in
`settings.gradle.kts` yet, so it does not affect aggregate builds.

## What it needs to be

- **Loader:** Forge (LexForge) 1.21.1 = 51.x. `META-INF/mods.toml`, `modLoader="javafml"`,
  `loaderVersion="[51,)"`, dependency `modId="forge"` (mandatory), minecraft `[1.21,1.21.2)`.
- **Namespace:** `net.minecraftforge.*` entrypoint (NOT `net.neoforged.*`).
- **Toolchain:** ForgeGradle 6 + SpongePowered mixin gradle plugin, JDK 21.
- **Mappings:** CONFIRM Forge 51.x runtime mapping at 1.21.1 (SRG vs official mojmap).
  If SRG at runtime → needs a generated `chunksmith.refmap.json` (mirror the
  `neoforge-1.20.1` FG6 setup). If mojmap-native → no refmap (mirror the MDG variants).

## Build it by combining two existing templates

1. **Forge-namespace entrypoint + FG6 toolchain + mods.toml** → copy the pattern from
   `neoforge-1.20.1/` (it is already a `net.minecraftforge.*` FG6 build with mods.toml
   and SRG refmap — the proven Forge-on-1.20.1 shape).
2. **1.21.1 API adaptations** (Util/Identifier/permissions/etc. for the mixins + platform
   layer) → take from `neoforge-1.21.1/` (same MC version, already adapted).
   Keep `common` + `nbt` shaded; do NOT exclude `version.properties` (that bug crashed
   neoforge-1.20.1/1.20.4 — see mod/1.20.x commit 4d38866).

## Deploy target

- A **Forge 1.21.1** ModrinthApp profile (Forge loader). Does not exist yet — must be
  created. The jar CANNOT go in the `NeoForge 1.21.1` profile.
- Output jar name: `Chunksmith-Forge-1.21.1-<version>.jar`. The mod-deploy tool will then
  match it to the Forge 1.21.1 profile by its embedded `mods.toml` (forge loader).

## When implementing

- Register `chunksmith-forge-1.21.1` in `settings.gradle.kts` (projectDir = `forge-1.21.1`).
- Add a `config/mods/chunksmith.json` staging entry is not needed (same staging dir).
- Verify in-jar: `mods.toml` (forge), `version.properties`, refmap (if SRG), mixins.json,
  Java 17/21 bytecode as appropriate, then deploy via `deploy_profiles.py`.
