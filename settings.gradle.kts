rootProject.name = "chunksmith"

include("chunksmith-nbt")
project(":chunksmith-nbt").projectDir = file("nbt")

include("chunksmith-common")
project(":chunksmith-common").projectDir = file("common")
