// ChunkSmith Fabric cell - MC 1.21.5 (single-cross-version-source model, Goal 5).
// MODERN reference cell. Compiles the Cog-generated shared_minecraft output (gen/) plus the
// per-cell platform/entrypoint seam, and pulls MC-agnostic code from :chunksmith-common
// (= ../../shared_common). Version drift in the shared mixins is resolved by Cog (cog-gen.ps1),
// NOT reflection, because pre-26 Fabric runs on the intermediary runtime.
//
// Tooling: fabric-loom 1.12.7 + Gradle 8.14 + JDK 21 (the proven 1.21.4-era set - this cell built
// green on it before consolidation). -Xlint:all + zero warnings.

plugins {
    id("fabric-loom") version "1.12.7"
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

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    modCompileOnly("me.lucko:fabric-permissions-api:0.3.1")

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
