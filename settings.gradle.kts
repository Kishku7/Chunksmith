pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.minecraftforge.net")
        maven("https://maven.architectury.dev/")
        maven("https://maven.neoforged.net/releases/")
        maven("https://repo.codemc.io/repository/relativitymc/")
    }
}

rootProject.name = "chunksmith"

include("chunksmith-nbt")
project(":chunksmith-nbt").projectDir = file("nbt")

include("chunksmith-common")
project(":chunksmith-common").projectDir = file("common")

// --- mod/26.2.x variants (mojmap-native, neo-loom, JDK 25) — PRE-RELEASE LINE ---
// This is a MOD branch: only the Fabric + NeoForge mod variants belong here.
// The Paper/Spigot/Folia plugin lives on plugin/26.2.x.
//
// 26.2 is a Minecraft pre-release line. Per the backport plan: branch code only,
// NO GitHub Release until 26.2 is stable (Modrinth beta only).
//
//   fabric-26.2   -> MC 26.2-rc-2 (depends >=26.2-)  [BUILT]
//
// neoforge-26.2 source is present in the repo (neoforge-26.2/, retargeted to NeoForge
// 26.2) but is intentionally NOT wired into this build yet. There is no public
// NeoForge 26.2 release; building it requires a locally-built NeoForge 26.2 alpha,
// and neo-loom 1.16.0-alpha.4 cannot consume that local userdev artifact (its
// afterEvaluate Minecraft-setup mutates every repository's content descriptor AFTER
// the local repo has already been used, throwing "cannot mutate content repository
// descriptor after use"). Re-include the module here once a public NeoForge 26.2
// release exists or neo-loom can consume the local userdev:
//   include("chunksmith-neoforge-26.2")
//   project(":chunksmith-neoforge-26.2").projectDir = file("neoforge-26.2")

include("chunksmith-fabric-26.2")
project(":chunksmith-fabric-26.2").projectDir = file("fabric-26.2")
