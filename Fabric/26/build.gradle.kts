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
val mcVersion = (project.findProperty("mcVersion") ?: "26.3-snapshot-3").toString()
val fabricApiVersion = (project.findProperty("fabricApiVersion") ?: "0.154.3+26.3").toString()
val mcDep = (System.getenv("MC_DEP") ?: ">=26.3-")
// Resource pack_format is per-26.X (26.1=84, 26.2=88, 26.3=91); build-all overrides via
// PACK_FORMAT so each emitted jar carries its own correct value. Above the MC 26 SERVER_DATA
// lastPreMinorVersion (81) the strict pack.mcmeta parser REQUIRES min_format/max_format, so the
// pack.mcmeta template declares min_format=max_format=pack_format (an exact single-format range).
// Default = 26.3 (dev tip).
val packFormat = (System.getenv("PACK_FORMAT") ?: "91")

sourceSets["main"].java.srcDir("gen/src/main/java")

dependencies {
    minecraft(group = "com.mojang", name = "minecraft", version = mcVersion)
    implementation(group = "net.fabricmc", name = "fabric-loader", version = "0.19.3")
    implementation(group = "net.fabricmc.fabric-api", name = "fabric-api", version = fabricApiVersion)
    compileOnly(group = "me.lucko", name = "fabric-permissions-api", version = "0.7.0")
    // voxy: OPTIONAL soft dependency (LOD generation). All-Rights-Reserved -- never vendored,
    // never shipped; the jar is gitignored and compileOnly. MC 26.x mods ship mojmap-named,
    // so no remapping is required.
    compileOnly(files("libs/voxy-0.2.16-beta.jar"))
    // Distant Horizons: OPTIONAL soft dependency. LGPL-3 -- we link against its published API,
    // never vendor it. The jar is gitignored and compileOnly.
    compileOnly(files("libs/DistantHorizons-3.2.0-b-26.1.2.jar"))
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
        inputs.property("packFormat", packFormat)
        inputs.property("mcDep", mcDep)
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
        filesMatching("pack.mcmeta") {
            expand("packFormat" to packFormat)
        }
    }
    shadowJar {
        configurations = listOf(shade)
        archiveFileName.set("${project.property("artifactName")}-Fabric-${project.version}.jar")
    }
}
