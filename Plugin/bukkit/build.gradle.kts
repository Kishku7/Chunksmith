repositories {
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

dependencies {
    // 26.2 is a Minecraft pre-release. Spigot does not publish a 26.2 spigot-api yet,
    // so the Bukkit entrypoint compiles against the latest stable 26.1.2 spigot-api;
    // the Bukkit API surface Chunksmith uses is identical across 26.1 -> 26.2, and the
    // jar is forward-compatible on a 26.2 server (see README pre-release note).
    compileOnly(group = "org.spigotmc", name = "spigot-api", version = "26.1.2-R0.1-SNAPSHOT")
    compileOnly(group = "org.apache.logging.log4j", name = "log4j-core", version = "2.25.2")
    compileOnly(group = "com.github.Puremin0rez", name = "WorldBorder", version = "1.19") {
        isTransitive = false
    }
    implementation(group = "org.bstats", name = "bstats-bukkit", version = "3.0.2")
    implementation(project(":chunksmith-common"))
    implementation(project(":chunksmith-platform"))
}

tasks {
    processResources {
        filesMatching("plugin.yml") {
            expand(
                "name" to project.property("artifactName")!!,
                "version" to project.version,
                "group" to project.group,
                "author" to project.property("author")!!,
                "description" to project.property("description")!!,
            )
        }
    }
    shadowJar {
        minimize {
            exclude(project(":chunksmith-common"))
            exclude(project(":chunksmith-platform"))
        }
        relocate("org.bstats", "${project.group}.${rootProject.name}.lib.bstats")
        manifest {
            attributes("paperweight-mappings-namespace" to "mojang")
        }
        archiveFileName.set("${project.property("artifactName")}-Bukkit-${project.version}.jar")
    }
}
