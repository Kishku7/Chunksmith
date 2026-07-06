// ChunkSmith NeoForge cell - MC 1.21.8 (single-cross-version-source model, Goal 5).
// modern_pre11 era. Compiles the Cog-generated shared_minecraft output (gen/) plus the per-cell
// platform/entrypoint seam, and pulls MC-agnostic code from :chunksmith-common (= ../../shared_common).
// Version drift in the shared mixins is resolved by Cog (cog-gen.ps1), NOT reflection.
//
// Tooling: ModDevGradle 2.0.141 + Gradle 8.14 + JDK 21 (the proven NeoForge 1.21.x set). Mojmap-native,
// NO mixin annotation processor + NO refmap (NeoForge 20.2+ resolves mixin targets by mojmap name at
// runtime; the same mixin classes are compile-validated on the Fabric cell). -Xlint:all + zero warnings.

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

dependencies {
    // Mixin annotations only (compile classpath). NO annotation processor - mojmap-native runtime.
    compileOnly("org.spongepowered:mixin:$mixinVersion")

    implementation(project(":chunksmith-common"))
    shade(project(":chunksmith-common"))
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.add("-Xlint:all")
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
                "neoforgeRange" to (System.getenv("NEOFORGE_RANGE") ?: "[21.5,21.9)"),
                "mcRange" to (System.getenv("MC_RANGE") ?: "[1.21.5,1.21.9)")
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
