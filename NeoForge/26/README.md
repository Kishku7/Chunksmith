# Chunksmith - NeoForge (Minecraft 26.1 - 26.3)

The NeoForge mod build (ModDevGradle toolchain). One version-agnostic source builds every
supported 26.x NeoForge target. The only per-version differences are the `neoforge` artifact
version and the two `neoforge.mods.toml` version ranges; the compiled code is identical across
versions, so adding a target is a one-row change in `scripts/build-neoforge.ps1`.

Shared code: the MC-agnostic core is [`../shared_common`](../shared_common); the
Minecraft-touching mod layer (Mixins and accessors that keep big pregens safe on vanilla) is
generated per cell into `gen/` from [`../../_codegen/cog_sources`](../../_codegen/cog_sources),
which is the single source of truth for it. Both are shaded into each jar.

## Build

From the repo root:

    pwsh scripts/build-neoforge.ps1          # all targets -> dist/
    pwsh scripts/build-neoforge.ps1 26.2     # one target

Targets: 26.1 (neoforge 26.1.2.107), 26.2 (neoforge 26.2.0.84), 26.3 (neoforge 26.3.0.1-beta).
`scripts/build-neoforge.ps1` is the canonical matrix; the `gradle.properties` default mirrors it
for a bare `gradlew` run. The declared `neoforge` version RANGES are deliberately wider than the
build pin, so compiling against the newest build does not raise the floor for existing users.
Toolchain: net.neoforged.moddev (MDG) **2.0.147**, mixin 0.8.5, Java 25, mojmap-native.

> **MDG 2.0.147 is a floor, not a preference.** On **2.0.141** the 26.3 cell dies inside
> `:createMinecraftArtifacts`, while Gradle is recompiling Minecraft's OWN sources and before a
> line of mod code compiles: `HolderSet$1 ... attempting to assign weaker access privileges`. It
> reads exactly like a broken loader -- the dedicated server from the same installer boots fine --
> so if you see that, bump MDG before you go looking at NeoForge.
