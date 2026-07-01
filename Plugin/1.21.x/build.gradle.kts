// ChunkSmith plugin cell -- MC 1.21.x (Paper / Spigot / Folia). STANDALONE build, mirroring the
// mod cells: consumes the MC-agnostic core from :chunksmith-common (= ../../shared_common, in place
// and read-only) and the shared plugin source from ../shared_plugin. Ships ONE jar:
// Chunksmith-plugin-1.21.x.jar. api-version 1.21, Java 21.
plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.4.0"
}

allprojects {
    group = project.property("group") as String
    version = project.property("version") as String
    repositories {
        mavenCentral()
    }
}

// shared_common carries no build plugins of its own; the consumer applies java-library + a
// toolchain (exactly as the mod cells do).
project(":chunksmith-common") {
    plugins.apply("java-library")
    the<JavaPluginExtension>().toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
    }
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

dependencies {
    // folia-api is the superset (Bukkit < Spigot < Paper < Folia): the whole shared plugin source
    // compiles against this one classpath; the runtime Platform facade picks the flavour.
    compileOnly("dev.folia:folia-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("org.apache.logging.log4j:log4j-core:2.25.2")
    compileOnly("com.github.Puremin0rez:WorldBorder:1.19") { isTransitive = false }
    implementation("org.bstats:bstats-bukkit:3.0.2")
    implementation(project(":chunksmith-common"))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

sourceSets {
    main {
        java {
            srcDir("../shared_plugin/bukkit/src/main/java")
            srcDir("../shared_plugin/platform/src/main/java")
        }
        resources {
            srcDir("../shared_plugin/bukkit/src/main/resources")
        }
    }
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.add("-Xlint:all")
    }
    processResources {
        filesMatching("plugin.yml") {
            expand(
                "name" to project.property("artifactName")!!,
                "version" to project.version,
                "group" to project.group,
                "author" to project.property("author")!!,
                "description" to project.property("description")!!,
                "apiVersion" to "1.21",
            )
        }
    }
    jar {
        archiveClassifier.set("noshade")
    }
    shadowJar {
        archiveFileName.set("Chunksmith-plugin-1.21.x.jar")
        minimize {
            exclude(project(":chunksmith-common"))
        }
        relocate("org.bstats", "${project.group}.chunksmith.lib.bstats")
        manifest {
            attributes("paperweight-mappings-namespace" to "mojang")
        }
    }
    build {
        dependsOn(shadowJar)
    }
}
