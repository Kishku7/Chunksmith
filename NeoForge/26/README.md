# Chunksmith - NeoForge (Minecraft 26.1 - 26.2)

The NeoForge mod build (ModDevGradle toolchain). One version-agnostic source builds every
supported 26.x NeoForge target. MC 26.3 has no NeoForge release yet, so it is not built
here; adding it later is a one-row change in `build-all-neoforge.ps1`. The only per-version
differences are the `neoforge` artifact version and the two `neoforge.mods.toml` version
ranges; the compiled code is identical across versions.

Shared code: the MC-agnostic core is [`../shared_common`](../shared_common); the
Minecraft-touching mod layer (Mixins/accessors that keep big pre-gens safe on vanilla) is
[`../shared_minecraft`](../shared_minecraft). See those READMEs for feature detail.

## Build

From the repo root:

    pwsh build-all-neoforge.ps1          # all targets -> dist/
    pwsh build-all-neoforge.ps1 26.2     # one target

Targets: 26.1 (neoforge 26.1.0.15-beta), 26.2 (neoforge 26.2.0.1-beta).
Toolchain: net.neoforged.moddev (MDG) 2.0.141, mixin 0.8.5, Java 25, mojmap-native.
