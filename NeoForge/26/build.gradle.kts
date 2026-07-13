plugins {
    id("net.neoforged.moddev") version "2.0.141"
    id("java-library")
    id("maven-publish")
    id("com.gradleup.shadow") version "9.4.0"
}

allprojects {
    plugins.apply("java-library")
    plugins.apply("maven-publish")
    plugins.apply("com.gradleup.shadow")

    group = "${project.property("group")}"
    version = "${project.property("version")}"

    repositories {
        mavenCentral()
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
        withSourcesJar()
    }

    tasks {
        withType<JavaCompile> {
            options.encoding = "UTF-8"
            options.release = 25
            // DH's standalone API artifact is JvmDowngrader-processed: its class files carry
        // xyz.wagyourtail.jvmdg NestHost/NestMembers annotations, and the artifact does not ship those
        // annotation types. javac's `classfile` lint reports that while READING the dependency -- it says
        // nothing about our code. Disable that ONE category so the zero-warning gate still means
        // something for our own source. It is ONE -Xlint token (a lint-category disable), not a flag.
        options.compilerArgs.add("-Xlint:all,-classfile")
        }
    }
}

// NeoForge variant for MC 26.2 -- ModDevGradle toolchain (matches the other Kishku7 mods' 26.2
// NeoForge builds + chunksmith's own NeoForge/1.20.6). MDG self-manages the neoforged maven and
// builds against public neoforge 26.2.0.1-beta, unlike neo-loom 1.16 (cannot resolve 26.2 userdev).
// JDK 25, mojmap-native, NO mixin AP / NO refmap.

val neoforgeVersion = (project.findProperty("neoforgeVersion") ?: "26.2.0.1-beta").toString()
val mixinVersion = "0.8.5"
// Resource pack_format is per-26.X (26.1=84, 26.2=88); build-all overrides via PACK_FORMAT so each
// emitted jar carries its own correct value. On MC 26 the SERVER_DATA lastPreMinorVersion is 81, so
// ANY pack_format > 81 (all 26.x) triggers the strict parser's mandatory min_format/max_format demand
// -- the pack.mcmeta template therefore declares min_format=max_format=pack_format (an exact single-
// format range) so each jar validates on the strict NeoForge datapack path. Default = 26.2.
val packFormat = (System.getenv("PACK_FORMAT") ?: "88")

neoForge {
    version = neoforgeVersion
    mods {
        create("chunksmith") {
            sourceSet(sourceSets["main"])
        }
    }
}

val shade: Configuration by configurations.creating

sourceSets["main"].java.srcDir("gen/src/main/java")

repositories {
    // The Modrinth maven -- the ONLY place DH's standalone API artifact is published.
    maven("https://api.modrinth.com/maven") {
        content {
            includeGroup("maven.modrinth")
        }
    }
}

dependencies {
    compileOnly("org.spongepowered:mixin:$mixinVersion")
    // Distant Horizons -- the LOD renderer this cell feeds in SINGLEPLAYER. OPTIONAL soft dependency:
    // compiled against, NEVER shipped (LGPL-3; not ours to redistribute). The jar lives in the gitignored
    // ../../libs/. Everything we touch is com.seibel.* and names no Minecraft type, so the same jar works
    // on every loader and every runtime mapping -- nothing to remap, hence a plain file dependency. DH
    // publishes one artifact per MC line and the 26.1.2 one carries the whole 26 line's API.
    //
    // No voxy here: voxy is FABRIC-ONLY, and NOT ONE voxy fork supports 26.x on NeoForge at all
    // (lod-ecosystem.md). The voxy seam is compile-time absent on this cell.
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
    implementation(project(":chunksmith-common"))
    shade(project(":chunksmith-common"))
}

tasks {
    processResources {
        inputs.property("version", project.version)
        inputs.property("packFormat", packFormat)
        filesMatching("META-INF/neoforge.mods.toml") {
            expand(
                "github" to project.property("github")!!,
                "id" to project.property("modId")!!,
                "version" to project.version,
                "name" to project.property("artifactName")!!,
                "author" to project.property("author")!!,
                "description" to project.property("description")!!,
                "neoforgeRange" to (System.getenv("NEOFORGE_RANGE") ?: "[26.2.0-alpha,)"),
                "mcRange" to (System.getenv("MC_RANGE") ?: "[26.2,)")
            )
        }
        filesMatching("pack.mcmeta") {
            expand("packFormat" to packFormat)
        }
    }
    shadowJar {
        configurations = listOf(shade)
        archiveClassifier.set("")
        archiveFileName.set("${project.property("artifactName")}-NeoForge-${project.version}.jar")
        manifest {
            attributes(
                mapOf(
                    "Implementation-Title" to rootProject.name,
                    "Implementation-Version" to project.version,
                    "Implementation-Vendor" to project.property("author")!!,
                    "MixinConfigs" to "chunksmith.mixins.json"
                )
            )
        }
    }
    jar {
        archiveClassifier.set("slim")
    }
    build {
        dependsOn(shadowJar)
    }
}
