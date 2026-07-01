// ChunkSmith plugin cell -- MC 1.20.x (Paper / Spigot / Folia). STANDALONE build, mirroring the mod
// cells: consumes the MC-agnostic core from :chunksmith-common (= ../../shared_common, in place and
// read-only) and the shared plugin source from ../shared_plugin. Ships ONE jar:
// Chunksmith-plugin-1.20.x.jar. api-version 1.20.
//
// Java note: compile on JDK 21 (--release 17 output). folia-api 1.20.6 is published as a Java-21
// artifact (MC 1.20.5+ requires Java 21), so its module metadata demands JVM 21 and its class files
// need JDK 21 to be read. We keep --release 17 so the emitted plugin bytecode (major 61) still loads
// on the Java-17 servers of the 1.20.1-1.20.4 sub-line; we only compileOnly the API (never shade it),
// and raise the consumer TargetJvmVersion attribute to 21 so the Java-21 API jar resolves.
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

// shared_common carries no build plugins of its own; the consumer applies java-library + a toolchain
// (exactly as the mod cells do). Held at --release 17 (major 61) so its shaded classes load on the
// Java-17 sub-line alongside the cell's own --release-17 output.
project(":chunksmith-common") {
    plugins.apply("java-library")
    the<JavaPluginExtension>().toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(17)
    }
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

dependencies {
    // folia-api is the superset (Bukkit < Spigot < Paper < Folia): the whole shared plugin source
    // compiles against this one classpath; the runtime Platform facade picks the flavour.
    compileOnly("dev.folia:folia-api:1.20.6-R0.1-SNAPSHOT")
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

// Admit the Java-21 folia-api jar despite --release 17 (see header note).
configurations.matching {
    it.name.lowercase().endsWith("compileclasspath") || it.name.lowercase().endsWith("runtimeclasspath")
}.configureEach {
    attributes {
        attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
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
        options.release.set(17)
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
                "apiVersion" to "1.20",
            )
        }
    }
    jar {
        archiveClassifier.set("noshade")
    }
    shadowJar {
        archiveFileName.set("Chunksmith-plugin-1.20.x.jar")
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
