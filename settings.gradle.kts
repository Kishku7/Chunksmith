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

// --- mod/1.21.x backport variants (mojmap, JDK21) ---
// Plugin (spigot/paper/folia) is intentionally NOT included in this branch's
// build yet. It is re-added per the backport plan when ported.
//
// This run stands up the 1.21.1 ANCHOR PAIR. The 1.21.x line will sub-split later
// (candidate boundaries 1.21.2 / 1.21.5 / 1.21.6 / 1.21.9 / 1.21.11 for our targets);
// further variant modules get registered here as those splits are cut.
//
// fabric-1.21.1   = fabric-loom + officialMojangMappings, JDK21.
// neoforge-1.21.1 = net.neoforged:neoforge:21.1.x via ModDevGradle, JDK21, mojmap-native.

include("chunksmith-fabric-1.21.1")
project(":chunksmith-fabric-1.21.1").projectDir = file("fabric-1.21.1")

include("chunksmith-neoforge-1.21.1")
project(":chunksmith-neoforge-1.21.1").projectDir = file("neoforge-1.21.1")
