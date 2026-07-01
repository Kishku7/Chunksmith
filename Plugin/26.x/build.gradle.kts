// ChunkSmith plugin cell -- MC 26.x (Paper / Spigot / Folia). STANDALONE build, mirroring the mod
// cells: consumes the MC-agnostic core from :chunksmith-common (= ../../shared_common, in place and
// read-only) and the shared plugin source from ../shared_plugin. Ships ONE jar:
// Chunksmith-plugin-26.x.jar. Java 25 toolchain, --release 21 bytecode.
//
// folia-api 26.1.2 publishes Gradle module metadata demanding JVM 25, but --release 21 tags the
// resolvable classpaths with TargetJvmVersion 21, which would REJECT the dep. We only compileOnly
// the API (never shade it), so the emitted bytecode still respects --release 21 (major 65, loads on
// Java 21+); we just raise the consumer TargetJvmVersion attribute back to 25 on the compile/runtime
// classpaths so the Java-25 API jar resolves.
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
// toolchain (exactly as the mod cells do). Held at --release 21 (major 65) so its shaded classes
// load on Java 21+ alongside the cell's own --release-21 output.
project(":chunksmith-common") {
    plugins.apply("java-library")
    the<JavaPluginExtension>().toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
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
    compileOnly("dev.folia:folia-api:26.1.2.build.8-stable")
    compileOnly("org.apache.logging.log4j:log4j-core:2.25.2")
    compileOnly("com.github.Puremin0rez:WorldBorder:1.19") { isTransitive = false }
    implementation("org.bstats:bstats-bukkit:3.0.2")
    implementation(project(":chunksmith-common"))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// Admit the Java-25 folia-api jar despite --release 21 (see header note).
configurations.matching {
    it.name.lowercase().endsWith("compileclasspath") || it.name.lowercase().endsWith("runtimeclasspath")
}.configureEach {
    attributes {
        attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
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
                "apiVersion" to "26.1",
            )
        }
    }
    jar {
        archiveClassifier.set("noshade")
    }
    shadowJar {
        archiveFileName.set("Chunksmith-plugin-26.x.jar")
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
