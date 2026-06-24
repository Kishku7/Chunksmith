pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.minecraftforge.net")
        maven("https://maven.architectury.dev/")
        maven("https://repo.codemc.io/repository/relativitymc/")
    }
}

rootProject.name = "chunksmith-plugin"

include("chunksmith-common")
project(":chunksmith-common").projectDir = file("../shared_common")

include("chunksmith-bukkit")
project(":chunksmith-bukkit").projectDir = file("bukkit")

include("chunksmith-platform")
project(":chunksmith-platform").projectDir = file("platform")