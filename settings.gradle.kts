pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.minecraftforge.net")
        maven("https://maven.architectury.dev/")
        maven("https://repo.codemc.io/repository/relativitymc/")
    }
}

rootProject.name = "chunky"

include("chunky-nbt")
project(":chunky-nbt").projectDir = file("nbt")

include("chunky-common")
project(":chunky-common").projectDir = file("common")

include("chunky-spigot")
project(":chunky-spigot").projectDir = file("plugin/spigot")

include("chunky-paper")
project(":chunky-paper").projectDir = file("plugin/paperMC")

include("chunky-folia")
project(":chunky-folia").projectDir = file("plugin/folia")

include("chunky-fabric")
project(":chunky-fabric").projectDir = file("mods/fabric")

include("chunky-neoforge")
project(":chunky-neoforge").projectDir = file("mods/neoforge")
