plugins {
    id("fabric-loom") version "1.10.5"
    id("com.gradleup.shadow") version "8.3.5"
}

repositories {
    maven("https://maven.nucleoid.xyz/")
}

// Covers the JDK17 + ChunkResult band of the 1.20 family: MC 1.20.2 - 1.20.4.
// Differs from fabric-1.20.1 ONLY in: fabric-api/loader bump to 1.20.4 and
// ServerChunkCacheMixin's getChunkFutureMainThread return type
// (Either<ChunkAccess,ChunkLoadingFailure> -> ChunkResult<ChunkAccess>, the 1.20.2+ shape).
// Unlike fabric-1.20.6 this stays JDK17 (Java 21 floor is 1.20.5) and keeps ChunkStatus in
// the OLD package net.minecraft.world.level.chunk (the .status package move was 1.20.5).
val minecraftVersion = "1.20.4"
val fabricLoaderVersion = "0.16.10"
val fabricApiVersion = "0.97.3+1.20.4"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    withSourcesJar()
}

val shade: Configuration by configurations.creating

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    modCompileOnly("me.lucko:fabric-permissions-api:0.3.1")

    implementation(project(":chunksmith-common"))
    shade(project(":chunksmith-common"))
    shade(project(":chunksmith-nbt"))
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(17)
        options.compilerArgs.add("-Xlint:none")
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
