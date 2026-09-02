# Chunksmith - NeoForge (Minecraft 26.1 - 26.2)

The NeoForge mod build (ModDevGradle toolchain). One version-agnostic source builds every
supported 26.x NeoForge target. MC 26.3 has no NeoForge release yet, so it is not built
here; adding it later is a one-row change in `scripts/build-neoforge.ps1`. The only per-version
differences are the `neoforge` artifact version and the two `neoforge.mods.toml` version
ranges; the compiled code is identical across versions.

Shared code: the MC-agnostic core is [`../shared_common`](../shared_common); the
Minecraft-touching mod layer (Mixins and accessors that keep big pregens safe on vanilla) is
generated per cell into `gen/` from [`../../_codegen/cog_sources`](../../_codegen/cog_sources),
which is the single source of truth for it. Both are shaded into each jar.

## Build

From the repo root:

    pwsh scripts/build-neoforge.ps1          # all targets -> dist/
    pwsh scripts/build-neoforge.ps1 26.2     # one target

Targets: 26.1 (neoforge 26.1.2.101), 26.2 (neoforge 26.2.0.75). `scripts/build-neoforge.ps1`
is the canonical matrix; the `gradle.properties` default mirrors it for a bare `gradlew` run.
The declared `neoforge` version RANGES are deliberately wider than the build pin, so compiling
against the newest build does not raise the floor for existing users.
Toolchain: net.neoforged.moddev (MDG) 2.0.141, mixin 0.8.5, Java 25, mojmap-native.
