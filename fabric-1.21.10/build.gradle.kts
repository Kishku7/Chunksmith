plugins {
    id("fabric-loom") version "1.11.8"
    id("com.gradleup.shadow") version "8.3.5"
}

repositories {
    maven("https://maven.nucleoid.xyz/")
}

// mod/1.21.x ANCHOR — Fabric variant for MC 1.21.1.
// Templated off fabric-1.20.6 (JDK21, mojmap, ChunkResult era). Retargeted to 1.21.1:
// fabric-api/loader bumped to the proven 1.21.1 set (mirrors Dave's bank-vault 1.21.1),
// and the 1.20.6 -> 1.21.1 mojmap API diffs applied to the platform/mixin layer.
// Quilt rides this jar.
val minecraftVersion = "1.21.10"
val fabricLoaderVersion = "0.16.10"
val fabricApiVersion = "0.138.4+1.21.10"

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
