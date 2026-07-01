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

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
        withSourcesJar()
    }

    tasks {
        withType<JavaCompile> {
            options.encoding = "UTF-8"
            options.release = 25
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
