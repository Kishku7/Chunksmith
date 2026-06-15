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
// Plugin (spigot/paper/folia) is intentionally NOT included in this branch's build.
//
// CS-target break boundaries across the 1.21 line (verified by decompile/mapping diff,
// see BACKPORT_PLAN §7 mod/1.21.x):
//   1.21.2  -> emptyTicks/tickConnection appear (F2 keep-awake applicable)
//   1.21.5  -> CompoundTag.getString returns Optional (chunk-NBT status path)
//   1.21.9  -> recompile boundary (defensive; Entity re-intermediation upstream)
//   1.21.11 -> ChunkStorage removed, replaced by SimpleRegionStorage (F3 accessor retarget)
//
// Variant -> MC range:
//   fabric/neoforge-1.21.1  = MC 1.21   - 1.21.1   (keep-awake N/A)
//   fabric/neoforge-1.21.4  = MC 1.21.2 - 1.21.4   (keep-awake restored)
//   fabric/neoforge-1.21.8  = MC 1.21.5 - 1.21.8   (getString Optional path)
//   fabric/neoforge-1.21.10 = MC 1.21.9 - 1.21.10  (recompile boundary; source == 1.21.8)
//   fabric/neoforge-1.21.11 = MC 1.21.11          (SimpleRegionStorage F3 path)
//
// fabric-*   = fabric-loom + officialMojangMappings, JDK21.
// neoforge-* = net.neoforged:neoforge via ModDevGradle, JDK21, mojmap-native (no mixin AP / no refmap).

include("chunksmith-fabric-1.21.1")
project(":chunksmith-fabric-1.21.1").projectDir = file("fabric-1.21.1")

include("chunksmith-neoforge-1.21.1")
project(":chunksmith-neoforge-1.21.1").projectDir = file("neoforge-1.21.1")

include("chunksmith-fabric-1.21.4")
project(":chunksmith-fabric-1.21.4").projectDir = file("fabric-1.21.4")

include("chunksmith-neoforge-1.21.4")
project(":chunksmith-neoforge-1.21.4").projectDir = file("neoforge-1.21.4")

include("chunksmith-fabric-1.21.8")
project(":chunksmith-fabric-1.21.8").projectDir = file("fabric-1.21.8")

include("chunksmith-neoforge-1.21.8")
project(":chunksmith-neoforge-1.21.8").projectDir = file("neoforge-1.21.8")

include("chunksmith-fabric-1.21.10")
project(":chunksmith-fabric-1.21.10").projectDir = file("fabric-1.21.10")

include("chunksmith-neoforge-1.21.10")
project(":chunksmith-neoforge-1.21.10").projectDir = file("neoforge-1.21.10")

include("chunksmith-fabric-1.21.11")
project(":chunksmith-fabric-1.21.11").projectDir = file("fabric-1.21.11")

include("chunksmith-neoforge-1.21.11")
project(":chunksmith-neoforge-1.21.11").projectDir = file("neoforge-1.21.11")