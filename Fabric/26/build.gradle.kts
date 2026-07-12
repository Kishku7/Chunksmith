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
            // DH's standalone API artifact is JvmDowngrader-processed: its class files carry
        // xyz.wagyourtail.jvmdg NestHost/NestMembers annotations, and the artifact does not ship those
        // annotation types. javac's `classfile` lint reports that while READING the dependency -- it says
        // nothing about our code. Disable that ONE category so the zero-warning gate still means
        // something for our own source. It is ONE -Xlint token (a lint-category disable), not a flag.
        options.compilerArgs.add("-Xlint:all,-classfile")
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

repositories {
    // The Modrinth maven -- the ONLY place DH's standalone API artifact is published.
    maven("https://api.modrinth.com/maven") {
        content {
            includeGroup("maven.modrinth")
        }
    }
}

dependencies {
    minecraft(group = "com.mojang", name = "minecraft", version = mcVersion)
    implementation(group = "net.fabricmc", name = "fabric-loader", version = "0.19.3")
    implementation(group = "net.fabricmc.fabric-api", name = "fabric-api", version = fabricApiVersion)
    compileOnly(group = "me.lucko", name = "fabric-permissions-api", version = "0.7.0")
    // The LOD renderers this cell feeds in SINGLEPLAYER. OPTIONAL soft dependencies: compiled against,
    // NEVER shipped. voxy is All-Rights-Reserved and Distant Horizons is LGPL-3 -- neither is ours to
    // redistribute. Both jars live in the ONE gitignored ../../libs/ that every LOD cell now shares
    // (they were per-cell copies; one tree, one jar, one md5).
    //
    // voxy is a PLAIN compileOnly here: the 26.x line ships unobfuscated, so the published voxy 26 jar is
    // mojmap-native and there is nothing to remap. (The 1.21.11 cell cannot do this -- its voxy jar is
    // intermediary-mapped and needs modCompileOnly + prep-libs.py. See that cell's build file.)
    compileOnly(files("../../libs/voxy-0.2.16-beta+26.1.2.jar"))
    // Distant Horizons: everything we touch is com.seibel.* and names no Minecraft type, so this same jar
    // works on every loader and every runtime mapping.
    // Distant Horizons -- compiled against its STANDALONE API artifact, not the DH mod jar.
    //
    // DH publishes its API as a separate, MINECRAFT-AGNOSTIC artifact on the Modrinth maven: ONE 344 KB
    // jar covers every MC version and every loader, replacing the four 28 MB per-MC-line mod jars this
    // build used to pin. DH's own DhApi.READ_ME says to do exactly this -- "use the API jar in your build
    // script as a compile time dependency and the full DH jar as a runtime dependency".
    //
    // Chunksmith uses DH's PUBLIC API only (no mixin into DH -- that lives in Chunksmith-Client), so it
    // needs NO full DH mod jar at compile time AT ALL. Everything we touch (DhApi.Delayed.terrainRepo,
    // DhApiLevelLoadEvent, IDhApiLevelWrapper, DhApiChunk, DhApiResult, IDhApiWorldGenerator) is in the
    // API artifact and names no Minecraft type, so there is nothing to remap on any loader.
    //
    // Every API method we call has been signature-stable since DH 2.0.0-a across six API-major bumps, so
    // this ONE artifact honestly covers the whole wide DH range we claim. compileOnly and never shipped:
    // DH is an optional soft dependency and is LGPL.
    compileOnly("maven.modrinth:distanthorizonsapi:7.0.0")
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
