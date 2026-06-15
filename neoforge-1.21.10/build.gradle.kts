// mod/1.21.x ANCHOR — NeoForge variant for MC 1.21.1.
//
// Templated off neoforge-1.20.6, retargeted to NeoForge 21.1.x
// (coordinate net.neoforged:neoforge:21.1.x; mirrors Dave's bank-vault 1.21.1 neoforge_version).
// NeoForge 1.21.1 is mojmap-native at runtime (net.neoforged.* namespace), built with
// ModDevGradle. JDK 21, official Mojang mappings, feature-for-feature with fabric-1.21.1.
//
// Mixins: NO annotation processor + NO refmap. NeoForge 20.2+ is mojmap-native at runtime, so dev
// names == runtime names and the FML mixin loader resolves @Inject/@Redirect/@Accessor/@Invoker
// targets directly by their mojmap name. The SpongePowered mixin AP CANNOT be used here for MC
// 1.21.1 under MDG: its bundled ObfuscationServiceMCP [searge,notch] always activates and demands a
// searge mapping for every string method target (tickServer, placeInChunk, ensureCanWrite,
// readAdditionalSaveData) that does not exist for a mojmap-native build, hard-failing the compile
// ("Unable to locate obfuscation mapping for @Inject target ..."). No AP option suppresses it for
// @Inject/@Redirect. This matches the proven neoforge-1.20.4 path (mod/1.20.x Run 4). The same
// mixin classes are compile-validated on the fabric-1.21.1 variant (loom refmap) and were SRG/AP
// validated on the 1.20 line, so runtime resolution by mojmap name is sound.

plugins {
    id("net.neoforged.moddev") version "2.0.141"
    id("com.gradleup.shadow") version "8.3.5"
}

val neoforgeVersion = "21.10.64"
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
        archiveFileName.set("${project.property("artifactName")}-NeoForge-1.21.10-${project.version}.jar")
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
