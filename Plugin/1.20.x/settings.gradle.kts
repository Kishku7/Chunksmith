pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

rootProject.name = "chunksmith-plugin-1.20.x"

// MC-agnostic core, reused IN PLACE from the mod tree (read-only, exactly like the mod cells).
// From this cell (Plugin/1.20.x) that is two levels up: ../../shared_common.
include("chunksmith-common")
project(":chunksmith-common").projectDir = file("../../shared_common")
