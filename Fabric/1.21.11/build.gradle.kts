// ChunkSmith Fabric cell - MC 1.21.11 (single-cross-version-source model, Goal 5).
// MODERN reference cell. Compiles the Cog-generated shared_minecraft output (gen/) plus the
// per-cell platform/entrypoint seam, and pulls MC-agnostic code from :chunksmith-common
// (= ../../shared_common). Version drift in the shared mixins is resolved by Cog (cog-gen.ps1),
// NOT reflection, because pre-26 Fabric runs on the intermediary runtime.
//
// Tooling: fabric-loom 1.13.6 + Gradle 8.14 + JDK 21 (the proven 1.21.11 set - this cell built
// green on it before consolidation). -Xlint:all + zero warnings.

plugins {
    id("fabric-loom") version "1.13.6"
    id("com.gradleup.shadow") version "8.3.5"
}

allprojects {
    group = project.property("group") as String
    version = project.property("version") as String
    repositories {
        mavenCentral()
    }
}

// Ensure the plain-Java shared_common subproject gets the java-library plugin + JDK 21 toolchain.
project(":chunksmith-common") {
    plugins.apply("java-library")
    the<JavaPluginExtension>().toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    maven("https://maven.nucleoid.xyz/")
}

val minecraftVersion = project.property("minecraft_version") as String
val fabricLoaderVersion = project.property("fabric_loader_version") as String
val fabricApiVersion = project.property("fabric_api_version") as String

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

// Cog-generated shared_minecraft source (produced by cog-gen.ps1 for this MC version).
sourceSets["main"].java.srcDir("gen/src/main/java")

val shade: Configuration by configurations.creating

repositories {
    // The Modrinth maven -- the ONLY place DH's standalone API artifact is published.
    maven("https://api.modrinth.com/maven") {
        content {
            includeGroup("maven.modrinth")
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    modCompileOnly("me.lucko:fabric-permissions-api:0.3.1")

    // The LOD renderers this cell can feed in SINGLEPLAYER. OPTIONAL soft dependencies: compiled
    // against, NEVER shipped. voxy is All-Rights-Reserved and Distant Horizons is LGPL -- neither is
    // ours to redistribute. Both jars live in the gitignored ../../libs/.
    //
    // Distant Horizons is a PLAIN compileOnly: the whole surface we touch (DhApi.Delayed.terrainRepo,
    // DhApiLevelLoadEvent, IDhApiLevelWrapper, DhApiChunk, DhApiResult) is com.seibel.* and names no
    // Minecraft type at all, so there is nothing for loom to remap.
    // Distant Horizons -- compiled against its STANDALONE API artifact, not the DH mod jar.
    //
    // DH publishes its API as a separate, MINECRAFT-AGNOSTIC artifact on the Modrinth maven: ONE 344 KB
    // jar covers every MC version and every loader, replacing the four 28 MB per-MC-line mod jars this
    // build used to pin. DH's own DhApi.READ_ME says to do exactly this -- "use the API jar in your build
    // script as a compile time dependency and the full DH jar as a runtime dependency".
    //
    // Chunksmith uses DH's PUBLIC API only (no mixin into DH -- that lives in Chunksmith-Client), so it
    // needs NO full DH mod jar at compile time AT ALL. Everything we touch (DhApi.Delayed.terrainRepo,
    // DhApiLevelLoadEvent, IDhApiLevelWrapper, DhApiChunk, DhApiResult, IDhApiWorldGenerator) is in the
    // API artifact and names no Minecraft type, so there is nothing to remap on any loader.
    //
    // Every API method we call has been signature-stable since DH 2.0.0-a across six API-major bumps, so
    // this ONE artifact honestly covers the whole wide DH range we claim. compileOnly and never shipped:
    // DH is an optional soft dependency and is LGPL.
    compileOnly("maven.modrinth:distanthorizonsapi:7.0.0")
    // The FULL DH jar -- COMPILE-TIME ONLY, and needed for exactly one thing: the LOD client's
    // dedupe-gate mixin targets DH's INTERNAL DhClientLevel, which the API artifact above does not
    // carry. Never shipped (DH is LGPL); DH stays an OPTIONAL soft dep at runtime. DH names no
    // Minecraft type, so this one jar serves every cell -- nothing to remap, no per-MC pin.
    compileOnly(files("../../libs/DistantHorizons-3.2.0-b-1.21.1.jar"))
    // voxy, via the -loomcompat copy that scripts/prep-libs.py produces. TWO separate traps here:
    //   1. modCompileOnly, NOT compileOnly: the published voxy jar for this MC line is INTERMEDIARY-mapped
    //      (WorldIdentifier.of(net.minecraft.class_1937), rawIngest(..., class_2826, ..., class_2804)),
    //      unlike the 26.x jar which is mojmap-native. Loom must remap it or the adapter does not compile.
    //   2. that jar is stamped Fabric-Loom-Version: 1.16.2, and this cell's Loom (1.13.x -- a hard floor
    //      AND ceiling for 1.21.11) refuses any mod dep built with a newer Loom, at CONFIGURE time.
    //      prep-libs.py strips that one provenance line; run it once before building this cell.
    modCompileOnly(files("../../libs/voxy-0.2.16-beta+1.21.11-loomcompat.jar"))

    implementation(project(":chunksmith-common"))
    shade(project(":chunksmith-common"))
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21)
        // DH's standalone API artifact is JvmDowngrader-processed: its class files carry
        // xyz.wagyourtail.jvmdg NestHost/NestMembers annotations, and the artifact does not ship those
        // annotation types. javac's `classfile` lint reports that while READING the dependency -- it says
        // nothing about our code. Disable that ONE category so the zero-warning gate still means
        // something for our own source. It is ONE -Xlint token (a lint-category disable), not a flag.
        options.compilerArgs.add("-Xlint:all,-classfile")
    }
    processResources {
        filesMatching("fabric.mod.json") {
            expand(
                "id" to project.property("modId")!!,
                "version" to project.version,
                "name" to project.property("artifactName")!!,
                "description" to project.property("description")!!,
                "author" to project.property("author")!!,
                "github" to project.property("github")!!
            )
        }
    }
    shadowJar {
        configurations = listOf(shade)
        archiveClassifier.set("dev-shadow")
    }
    remapJar {
        inputFile.set(shadowJar.flatMap { it.archiveFile })
        dependsOn(shadowJar)
        archiveClassifier.set("")
        archiveFileName.set("${project.property("artifactName")}-Fabric-${minecraftVersion}-${project.version}.jar")
    }
    jar {
        archiveClassifier.set("noshade")
    }
    build {
        dependsOn(remapJar)
    }
}
