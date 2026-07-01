plugins {
    id("java-library")
    id("maven-publish")
    id("com.gradleup.shadow") version "9.4.0"
}

// The root project is a pure aggregator: it carries no source, so its own jar/shadowJar
// artifacts are empty noise. Disable them (and drop the root from the build graph's
// artifact set) so the ONLY jar this build ships is the shaded chunksmith-bukkit output,
// named chunksmith-<version>-plugin.jar (see bukkit/build.gradle.kts). shared_common
// (chunksmith-common) and the Paper/Folia helpers (chunksmith-platform) are library
// subprojects shaded INTO the bukkit jar, not published on their own.
tasks.named("jar") { enabled = false }
tasks.named("shadowJar") { enabled = false }
tasks.named("build") { setDependsOn(emptyList<Any>()) }

subprojects {
    plugins.apply("java-library")
    plugins.apply("maven-publish")
    plugins.apply("com.gradleup.shadow")

    group = "${project.property("group")}"
    version = "${project.property("version")}"

    repositories {
        mavenCentral()
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
    }

    // Emit Java 21 bytecode (release 21), NOT the JDK 25 default. plugin.yml declares
    // api-version 1.21, so the jar must LOAD on every 1.21.x server, which run on Java 21
    // (26+ runs Java 25). Java-25 bytecode (class major 69) throws UnsupportedClassVersionError
    // on a Java-21 runtime (max major 65), so the plugin never enabled on pre-26 Paper/Folia.
    // release 21 makes the class files major 65 = loadable on Java 21+ (all 1.21.x AND 26).
    // The TOOLCHAIN stays 25 on purpose: spigot-api/folia-api 26.1.2 are published with Gradle
    // module metadata requiring JVM 25, and Gradle's variant resolution keys off the toolchain
    // language version (not options.release), so a 21 toolchain would REJECT those compileOnly
    // deps. JDK 25 runs javac + resolves the deps; --release 21 caps the emitted bytecode.
    // The source proved release-17-clean, so release 21 is comfortably within reach.
    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
        withSourcesJar()
    }

    // options.release = 21 (below) tags the resolvable classpaths with TargetJvmVersion 21, and
    // Gradle variant resolution then REJECTS spigot-api / folia-api 26.1.2 (their module metadata
    // demands JVM 25). We only compile against those APIs (compileOnly, never shaded in), so raise
    // the consumer TargetJvmVersion attribute back to 25 on the compile/runtime classpaths to admit
    // the Java-25 API jars; the emitted plugin bytecode still respects --release 21 (major 65).
    configurations.matching {
        it.name.lowercase().endsWith("compileclasspath") || it.name.lowercase().endsWith("runtimeclasspath")
    }.configureEach {
        attributes {
            attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
        }
    }

    tasks {
        withType<JavaCompile> {
            options.encoding = "UTF-8"
            options.release = 21
            options.compilerArgs.add("-Xlint:all")
        }
        jar {
            archiveClassifier.set("noshade")
        }
        shadowJar {
            archiveClassifier.set("")
            archiveFileName.set("${project.property("artifactName")}-${project.version}.jar")
        }
        build {
            dependsOn(shadowJar)
        }
    }

    publishing {
        repositories {
            if (project.hasProperty("mavenUsername") && project.hasProperty("mavenPassword")) {
                maven {
                    credentials {
                        username = "${project.property("mavenUsername")}"
                        password = "${project.property("mavenPassword")}"
                    }
                    url = uri("https://repo.codemc.io/repository/maven-releases/")
                }
            }
        }
        publications {
            create<MavenPublication>("maven") {
                groupId = "${project.group}"
                artifactId = project.name
                version = "${project.version}"
                from(components["java"])
            }
        }
    }
}
