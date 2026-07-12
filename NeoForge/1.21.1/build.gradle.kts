// ChunkSmith NeoForge cell - MC 1.21.1 (single-cross-version-source model, Goal 5).
// transitional era. Compiles the Cog-generated shared_minecraft output (gen/) plus the per-cell
// platform/entrypoint seam, and pulls MC-agnostic code from :chunksmith-common (= ../../shared_common).
// Version drift in the shared mixins is resolved by Cog (cog-gen.ps1), NOT reflection.
//
// Tooling: ModDevGradle 2.0.141 + Gradle 8.14 + JDK 21 (the proven NeoForge 1.21.x set). Mojmap-native,
// NO mixin annotation processor + NO refmap. -Xlint:all + zero warnings.

plugins {
    id("net.neoforged.moddev") version "2.0.141"
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

val neoforgeVersion = project.property("neoforge_version") as String
val mixinVersion = "0.8.5"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

neoForge {
    version = neoforgeVersion
    mods {
        create("chunksmith") {
            sourceSet(sourceSets["main"])
        }
    }
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
    // Mixin annotations only (compile classpath). NO annotation processor - mojmap-native runtime.
    compileOnly("org.spongepowered:mixin:$mixinVersion")

    // Distant Horizons -- the LOD renderer this cell feeds in SINGLEPLAYER. OPTIONAL soft dependency:
    // compiled against, NEVER shipped (LGPL-3; not ours to redistribute). The jar lives in the gitignored
    // ../../libs/. Everything we touch is com.seibel.* and names no Minecraft type, so the same jar works
    // on every loader and every runtime mapping -- nothing to remap, hence a plain file dependency.
    //
    // No voxy here: voxy is FABRIC-ONLY, and not one of its NeoForge forks is published anywhere
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
        inputs.property("version", project.version)
        filesMatching("META-INF/neoforge.mods.toml") {
            expand(
                "github" to project.property("github")!!,
                "id" to project.property("modId")!!,
                "version" to project.version,
                "name" to project.property("artifactName")!!,
                "author" to project.property("author")!!,
                "description" to project.property("description")!!,
                "neoforgeRange" to (System.getenv("NEOFORGE_RANGE") ?: "[21.0,21.2)"),
                "mcRange" to (System.getenv("MC_RANGE") ?: "[1.21,1.21.2)")
            )
        }
    }
    shadowJar {
        configurations = listOf(shade)
        archiveClassifier.set("")
        archiveFileName.set("${project.property("artifactName")}-NeoForge-${project.property("minecraft_version")}-${project.version}.jar")
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
