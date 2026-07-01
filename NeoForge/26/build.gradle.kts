plugins {
    id("net.neoforged.moddev") version "2.0.141"
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
    }
}

// NeoForge variant for MC 26.2 -- ModDevGradle toolchain (matches the other Kishku7 mods' 26.2
// NeoForge builds + chunksmith's own NeoForge/1.20.6). MDG self-manages the neoforged maven and
// builds against public neoforge 26.2.0.1-beta, unlike neo-loom 1.16 (cannot resolve 26.2 userdev).
// JDK 25, mojmap-native, NO mixin AP / NO refmap.

val neoforgeVersion = (project.findProperty("neoforgeVersion") ?: "26.2.0.1-beta").toString()
val mixinVersion = "0.8.5"
// Resource pack_format is per-26.X (26.1=84, 26.2=88); build-all overrides via PACK_FORMAT so each
// emitted jar carries its own correct value. On MC 26 the SERVER_DATA lastPreMinorVersion is 81, so
// ANY pack_format > 81 (all 26.x) triggers the strict parser's mandatory min_format/max_format demand
// -- the pack.mcmeta template therefore declares min_format=max_format=pack_format (an exact single-
// format range) so each jar validates on the strict NeoForge datapack path. Default = 26.2.
val packFormat = (System.getenv("PACK_FORMAT") ?: "88")

neoForge {
    version = neoforgeVersion
    mods {
        create("chunksmith") {
            sourceSet(sourceSets["main"])
        }
    }
}

val shade: Configuration by configurations.creating

sourceSets["main"].java.srcDir("../../shared_minecraft/src/main/java")

dependencies {
    compileOnly("org.spongepowered:mixin:$mixinVersion")
    implementation(project(":chunksmith-common"))
    shade(project(":chunksmith-common"))
}

tasks {
    processResources {
        inputs.property("version", project.version)
        inputs.property("packFormat", packFormat)
        filesMatching("META-INF/neoforge.mods.toml") {
            expand(
                "github" to project.property("github")!!,
                "id" to project.property("modId")!!,
                "version" to project.version,
                "name" to project.property("artifactName")!!,
                "author" to project.property("author")!!,
                "description" to project.property("description")!!,
                "neoforgeRange" to (System.getenv("NEOFORGE_RANGE") ?: "[26.2.0-alpha,)"),
                "mcRange" to (System.getenv("MC_RANGE") ?: "[26.2,)")
            )
        }
        filesMatching("pack.mcmeta") {
            expand("packFormat" to packFormat)
        }
    }
    shadowJar {
        configurations = listOf(shade)
        archiveClassifier.set("")
        archiveFileName.set("${project.property("artifactName")}-NeoForge-${project.version}.jar")
        manifest {
            attributes(
                mapOf(
                    "Implementation-Title" to rootProject.name,
                    "Implementation-Version" to project.version,
                    "Implementation-Vendor" to project.property("author")!!,
                    "MixinConfigs" to "chunksmith.mixins.json"
                )
            )
        }
    }
    jar {
        archiveClassifier.set("slim")
    }
    build {
        dependsOn(shadowJar)
    }
}
