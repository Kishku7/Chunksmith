pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.architectury.dev/")
        maven("https://maven.neoforged.net/releases/")
        maven("https://maven.minecraftforge.net/")
        maven("https://repo.spongepowered.org/repository/maven-public/")
        maven("https://repo.codemc.io/repository/relativitymc/")
    }
}

rootProject.name = "chunksmith"

include("chunksmith-nbt")
project(":chunksmith-nbt").projectDir = file("nbt")

include("chunksmith-common")
project(":chunksmith-common").projectDir = file("common")

// --- mod/1.20.x backport variants (mojmap) ---
// Plugin (spigot/paper/folia) is intentionally NOT included in this branch's
// build yet. It is re-added per the backport plan when ported.
//
// Split (validated 2026-06-15 via decompiled 1.20.1 + 1.20.6 mojmap):
//   fabric/neoforge-1.20.1 (JDK17, Either) -> MC 1.20.1 only
//   fabric/neoforge-1.20.6 (JDK21, ChunkResult) -> MC 1.20.5 - 1.20.6
// The only CS-target break across the 1.20 line is ServerChunkCache.getChunkFutureMainThread
// returning Either<..> (1.20.1) vs ChunkResult<..> (1.20.2+); JDK17->21 floor is 1.20.5.

include("chunksmith-fabric-1.20.1")
project(":chunksmith-fabric-1.20.1").projectDir = file("fabric-1.20.1")

// NeoForge for MC 1.20.1 = the original NeoForge release, published as the Forge-coordinate
// fork net.neoforged:forge:1.20.1-47.1.x. At this MC version it still uses the
// net.minecraftforge.* API namespace and is binary-compatible with Forge 47.x, so it is
// built with ForgeGradle 6 + the SpongePowered Mixin plugin (ModDevGradle/NeoGradle are
// 1.20.2+). One jar, runs on NeoForge (and Forge) 1.20.1.
include("chunksmith-neoforge-1.20.1")
project(":chunksmith-neoforge-1.20.1").projectDir = file("neoforge-1.20.1")

// fabric-1.20.6 (JDK21, mojmap, fabric-loom) -> MC 1.20.5 - 1.20.6.
include("chunksmith-fabric-1.20.6")
project(":chunksmith-fabric-1.20.6").projectDir = file("fabric-1.20.6")

// neoforge-1.20.6 (JDK21, mojmap, ModDevGradle) -> MC 1.20.5 - 1.20.6.
// At 1.20.2+ NeoForge uses the net.neoforged.* namespace + the MDG toolchain
// (coordinate net.neoforged:neoforge:20.6.x). Registered once its module exists.
include("chunksmith-neoforge-1.20.6")
project(":chunksmith-neoforge-1.20.6").projectDir = file("neoforge-1.20.6")

// --- 1.20.2 - 1.20.4 gap variants (JDK17 + ChunkResult, OLD ChunkStatus package) ---
// fabric-1.20.4 (JDK17, mojmap, fabric-loom) -> MC 1.20.2 - 1.20.4.
// = the fabric-1.20.1 source + the ServerChunkCacheMixin Either->ChunkResult swap ONLY.
// ChunkStatus stays in net.minecraft.world.level.chunk (the .status move was 1.20.5).
include("chunksmith-fabric-1.20.4")
project(":chunksmith-fabric-1.20.4").projectDir = file("fabric-1.20.4")

// neoforge-1.20.4 (JDK17, mojmap, ModDevGradle) -> MC 1.20.2 - 1.20.4.
// At 1.20.2+ NeoForge uses the net.neoforged.* namespace + MDG (coordinate
// net.neoforged:neoforge:20.4.x), like 1.20.6 but JDK17 + old ChunkStatus package.
include("chunksmith-neoforge-1.20.4")
project(":chunksmith-neoforge-1.20.4").projectDir = file("neoforge-1.20.4")


// --- classic Forge (LexForge) variant ---
// forge-1.20.6 (JDK21, mojmap, ForgeGradle 6 + SpongePowered Mixin) -> MC 1.20.5 - 1.20.6.
// Standalone classic-Forge jar consuming net.minecraftforge:forge:1.20.6-50.2.8. The Java
// sources are the net.minecraftforge.* namespace shared with neoforge-1.20.1 plus the 3
// mojmap-1.20.6 API deltas (Either->ChunkResult, ChunkStatus package move). One jar, runs
// on classic Forge 1.20.5 - 1.20.6.
include("chunksmith-forge-1.20.6")
project(":chunksmith-forge-1.20.6").projectDir = file("forge-1.20.6")
