plugins {
    id("org.relativitymc.neo-loom") version "1.16.0-alpha.4"
}

val shade: Configuration by configurations.creating

repositories {
    maven("https://maven.neoforged.net/releases/")
}

dependencies {
    // NeoForge 26.2 has no public release yet; this variant builds against a
    // locally-built NeoForge 26.2 alpha installed in the local Maven repository
    // (mavenLocal() is declared in the root build's subprojects repositories block).
    minecraft(group = "com.mojang", name = "minecraft", version = "26.2-rc-2")
    forgeUserdev(group = "net.neoforged", name = "neoforge", version = "26.2.0-alpha.0+rc-2.20260614.200134", classifier = "userdev")
    implementation(project(":chunksmith-common"))
    shade(project(":chunksmith-common"))
}

loom {
    runs.forEach {
        it.ideConfigGenerated(true)
    }
    mods {
        create("main") {
            sourceSet(project.sourceSets.main.get())
            dependency(project.dependencyFactory.create(project(":chunksmith-common")))
        }
    }
}

tasks {
    processResources {
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
    jar {
        manifest {
            attributes(
                mapOf(
                    "Implementation-Title" to rootProject.name,
                    "Implementation-Version" to project.version,
                    "Implementation-Vendor" to project.property("author")!!
                )
            )
        }
    }
    shadowJar {
        configurations = listOf(shade)
        archiveFileName.set("${project.property("artifactName")}-NeoForge-${project.version}.jar")
    }
}
