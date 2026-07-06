pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.neoforged.net/releases/")
        maven("https://maven.minecraftforge.net/")
        maven("https://repo.spongepowered.org/repository/maven-public/")
        maven("https://repo.codemc.io/repository/relativitymc/")
    }
}

rootProject.name = "chunksmith-fabric-1.21.4"

include("chunksmith-common")
project(":chunksmith-common").projectDir = file("../../shared_common")
