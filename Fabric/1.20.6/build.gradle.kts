allprojects {
    group = project.property("group") as String
    version = project.property("version") as String
    repositories {
        mavenCentral()
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
    }
}

plugins {
    id("fabric-loom") version "1.10.5"
    id("com.gradleup.shadow") version "8.3.5"
}

repositories {
    maven("https://maven.nucleoid.xyz/")
}

// Covers the JDK21 half of the 1.20 family: MC 1.20.5 - 1.20.6.
// Differs from fabric-1.20.1 only in: JDK 17 -> 21, fabric-api/loader bump,
// and ServerChunkCacheMixin's getChunkFutureMainThread return type
// (Either<ChunkAccess,ChunkLoadingFailure> -> ChunkResult<ChunkAccess>, the 1.20.2+ shape).
val minecraftVersion = "1.20.6"
val fabricLoaderVersion = "0.16.10"
val fabricApiVersion = "0.100.8+1.20.6"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
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
        options.release.set(21)
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
