allprojects {
    group = project.property("group") as String
    version = project.property("version") as String
    repositories {
        mavenCentral()
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
    }
}

// NeoForge variant for MC 1.20.5 - 1.20.6.
//
// At 1.20.2+ NeoForge dropped the Forge fork and uses its own net.neoforged.* API namespace
// plus the ModDevGradle (MDG) toolchain (coordinate net.neoforged:neoforge:20.6.x). The setup is
// derived from MDG 2.0.x conventions pointed at NeoForge 20.6, with the entrypoint rewritten from
// the net.minecraftforge.* namespace (neoforge-1.20.1) to net.neoforged.*.
// JDK 21, official Mojang mappings, feature-for-feature with fabric-1.20.6.
//
// Mixins: NO annotation processor + NO refmap. NeoForge 20.2+ runs on official Mojang mappings at
// runtime, so the mojmap names the mixins are written against already match production - dev names
// == runtime names and the FML mixin loader resolves @Inject/@Redirect/@Accessor/@Invoker targets
// directly by their mojmap name; no SRG/intermediary remap is needed. The SpongePowered mixin AP
// CANNOT be used here on a clean tree: its bundled ObfuscationServiceMCP [searge,notch] always
// activates and demands a searge mapping for every string method target (tickServer, placeInChunk,
// ensureCanWrite, readAdditionalSaveData) that does not exist for a mojmap-native MDG build,
// hard-failing the compile ("Unable to locate obfuscation mapping for @Inject target ..."). No AP
// option suppresses it for @Inject/@Redirect. This matches the proven neoforge-1.21.1 / 26.1 /
// neoforge-1.20.4 path. The same mixin classes are compile-validated on the fabric-1.20.6 variant
// (loom refmap), so runtime resolution by mojmap name is sound.

plugins {
    id("net.neoforged.moddev") version "2.0.141"
    id("com.gradleup.shadow") version "8.3.5"
}

val neoforgeVersion = "20.6.139"
val mixinVersion = "0.8.5"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

neoForge {
    version = neoforgeVersion

    mods {
        create("chunksmith") {
            sourceSet(sourceSets["main"])
        }
    }
}

val shade: Configuration by configurations.creating

dependencies {
    // Mixin annotations only (compile classpath). NO annotation processor — see header note.
    compileOnly("org.spongepowered:mixin:$mixinVersion")

    implementation(project(":chunksmith-common"))
    shade(project(":chunksmith-common"))
    shade(project(":chunksmith-nbt"))
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.add("-Xlint:none")
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
                "description" to project.property("description")!!
            )
        }
    }
    shadowJar {
        configurations = listOf(shade)
        archiveClassifier.set("")
        archiveFileName.set("${project.property("artifactName")}-NeoForge-1.20.6-${project.version}.jar")
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
