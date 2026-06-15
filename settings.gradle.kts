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

// --- mod/1.20.x backport variants (mojmap, JDK 17) ---
// Plugin (spigot/paper/folia) is intentionally NOT included in this branch's
// build yet. It is re-added per the backport plan when ported.
include("chunksmith-fabric-1.20.1")
project(":chunksmith-fabric-1.20.1").projectDir = file("fabric-1.20.1")

// NeoForge for MC 1.20.1 = the original NeoForge release, published as the Forge-coordinate
// fork net.neoforged:forge:1.20.1-47.1.x. At this MC version it still uses the
// net.minecraftforge.* API namespace and is binary-compatible with Forge 47.x, so it is
// built with ForgeGradle 6 + the SpongePowered Mixin plugin (ModDevGradle/NeoGradle are
// 1.20.2+). One jar, runs on NeoForge (and Forge) 1.20.1.
include("chunksmith-neoforge-1.20.1")
project(":chunksmith-neoforge-1.20.1").projectDir = file("neoforge-1.20.1")
