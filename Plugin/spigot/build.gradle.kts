repositories {
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly(group = "org.spigotmc", name = "spigot-api", version = "1.21.4-R0.1-SNAPSHOT")
    compileOnly(group = "org.apache.logging.log4j", name = "log4j-core", version = "2.25.2")
    compileOnly(group = "com.github.Puremin0rez", name = "WorldBorder", version = "1.19") {
        isTransitive = false
    }
    implementation(group = "org.bstats", name = "bstats-bukkit", version = "3.0.2")
    implementation(project(":chunksmith-common"))
    implementation(project(":chunksmith-paper"))
    implementation(project(":chunksmith-folia"))
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
            exclude(project(":chunksmith-paper"))
            exclude(project(":chunksmith-folia"))
        }
        relocate("org.bstats", "${project.group}.${rootProject.name}.lib.bstats")
        manifest {
            attributes("paperweight-mappings-namespace" to "mojang")
        }
        archiveFileName.set("${project.property("artifactName")}-Bukkit-${project.version}.jar")
    }
}
