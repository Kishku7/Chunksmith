plugins {
    id("org.relativitymc.neo-loom") version "1.16.0-alpha.4"
    id("java-library")
    id("maven-publish")
    id("com.gradleup.shadow") version "9.4.0"
}

allprojects {
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
}

val shade: Configuration by configurations.creating

// Per-version dependency matrix. Defaults target the dev tip (26.3); the build-all
// script overrides via -PmcVersion / -PfabricApiVersion for each 26.x target.
val mcVersion = (project.findProperty("mcVersion") ?: "26.3-snapshot-1").toString()
val fabricApiVersion = (project.findProperty("fabricApiVersion") ?: "0.153.1+26.3").toString()
val mcDep = (System.getenv("MC_DEP") ?: ">=26.3-")

sourceSets["main"].java.srcDir("../../shared_minecraft/src/main/java")

dependencies {
    minecraft(group = "com.mojang", name = "minecraft", version = mcVersion)
    implementation(group = "net.fabricmc", name = "fabric-loader", version = "0.19.3")
    implementation(group = "net.fabricmc.fabric-api", name = "fabric-api", version = fabricApiVersion)
    compileOnly(group = "me.lucko", name = "fabric-permissions-api", version = "0.7.0")
    implementation(project(":chunksmith-common"))
    shade(project(":chunksmith-common"))
}

loom {
    runs.forEach {
        it.ideConfigGenerated(true)
    }
}

tasks {
    processResources {
        filesMatching("fabric.mod.json") {
            expand(
                "id" to project.property("modId")!!,
                "version" to project.version,
                "name" to project.property("artifactName")!!,
                "description" to project.property("description")!!,
                "author" to project.property("author")!!,
                "github" to project.property("github")!!,
                "mcDep" to mcDep
            )
        }
    }
    shadowJar {
        configurations = listOf(shade)
        archiveFileName.set("${project.property("artifactName")}-Fabric-${project.version}.jar")
    }
}
