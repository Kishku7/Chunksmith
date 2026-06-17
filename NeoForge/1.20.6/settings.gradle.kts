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

rootProject.name = "chunksmith-neoforge-1.20.6"

include("chunksmith-nbt")
project(":chunksmith-nbt").projectDir = file("../../nbt")

include("chunksmith-common")
project(":chunksmith-common").projectDir = file("../../common")
