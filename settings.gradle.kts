pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.architectury.dev/")
        maven("https://repo.codemc.io/repository/relativitymc/")
    }
}

rootProject.name = "chunksmith"

include("chunksmith-nbt")
project(":chunksmith-nbt").projectDir = file("nbt")

include("chunksmith-common")
project(":chunksmith-common").projectDir = file("common")

// --- mod/1.20.x backport: single fabric-1.20.1 variant (mojmap, JDK 17) ---
// Plugin (spigot/paper/folia) and neoforge modules are intentionally NOT included
// in this branch's build yet. They are re-added per the backport plan when ported.
include("chunksmith-fabric-1.20.1")
project(":chunksmith-fabric-1.20.1").projectDir = file("fabric-1.20.1")
