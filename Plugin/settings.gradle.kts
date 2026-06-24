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

include("chunksmith-spigot")
project(":chunksmith-spigot").projectDir = file("spigot")

include("chunksmith-paper")
project(":chunksmith-paper").projectDir = file("paperMC")

include("chunksmith-folia")
project(":chunksmith-folia").projectDir = file("folia")