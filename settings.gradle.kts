pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.minecraftforge.net")
        maven("https://maven.architectury.dev/")
        maven("https://repo.codemc.io/repository/relativitymc/")
    }
}

rootProject.name = "chunksmith"

include("chunksmith-nbt")
project(":chunksmith-nbt").projectDir = file("nbt")

include("chunksmith-common")
project(":chunksmith-common").projectDir = file("common")

include("chunksmith-spigot")
project(":chunksmith-spigot").projectDir = file("plugin/spigot")

include("chunksmith-paper")
project(":chunksmith-paper").projectDir = file("plugin/paperMC")

include("chunksmith-folia")
project(":chunksmith-folia").projectDir = file("plugin/folia")

include("chunksmith-fabric")
project(":chunksmith-fabric").projectDir = file("mods/fabric")

include("chunksmith-neoforge")
project(":chunksmith-neoforge").projectDir = file("mods/neoforge")
