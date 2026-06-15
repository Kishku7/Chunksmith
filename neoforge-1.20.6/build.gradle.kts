// NeoForge variant for MC 1.20.5 - 1.20.6.
//
// At 1.20.2+ NeoForge dropped the Forge fork and uses its own net.neoforged.* API namespace
// plus the ModDevGradle (MDG) toolchain (coordinate net.neoforged:neoforge:20.6.x). This is the
// FIRST MDG-based variant in this repo (the other mods only did 1.20.1 Forge-namespace + 1.21.11);
// the setup is derived from MDG 2.0.x conventions pointed at NeoForge 20.6, with the entrypoint
// rewritten from the net.minecraftforge.* namespace (neoforge-1.20.1) to net.neoforged.*.
// JDK 21, official Mojang mappings, feature-for-feature with fabric-1.20.6.
//
// Mixins: NeoForge 20.5+ runs on official Mojang mappings at runtime, so the mojmap names the
// mixins are written against already match production - no SRG/intermediary remap is needed. The
// SpongePowered mixin annotation processor is wired so the refmap is generated and, importantly,
// the mixin targets are VALIDATED at compile time against the real 1.20.6 classes.

plugins {
    id("net.neoforged.moddev") version "2.0.141"
    id("com.gradleup.shadow") version "8.3.5"
}

val neoforgeVersion = "20.6.139"
val mixinVersion = "0.8.5"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

neoForge {
    version = neoforgeVersion

    mods {
        create("chunksmith") {
            sourceSet(sourceSets["main"])
        }
    }
}

val shade: Configuration by configurations.creating

dependencies {
    // Mixin annotation processor: generates chunksmith.refmap.json and validates @Mixin/@Shadow/
    // @Accessor/@Invoker/@Redirect/@Inject targets against the compiled-against MC classes.
    compileOnly("org.spongepowered:mixin:$mixinVersion")
    annotationProcessor("org.spongepowered:mixin:$mixinVersion:processor")

    implementation(project(":chunksmith-common"))
    shade(project(":chunksmith-common"))
    shade(project(":chunksmith-nbt"))
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.add("-Xlint:none")
        // Tell the mixin AP where to write the refmap (matches "refmap" in chunksmith.mixins.json).
        options.compilerArgs.add("-AoutRefMapFile=${layout.buildDirectory.get().asFile}/refmap/chunksmith.refmap.json")
    }
    processResources {
        inputs.property("version", project.version)
        filesMatching("META-INF/neoforge.mods.toml") {
            expand(
                "github" to project.property("github")!!,
                "id" to project.property("modId")!!,
                "version" to project.version,
                "name" to project.property("artifactName")!!,
                "author" to project.property("author")!!,
                "description" to project.property("description")!!
            )
        }
    }
    shadowJar {
        configurations = listOf(shade)
        archiveClassifier.set("")
        archiveFileName.set("${project.property("artifactName")}-NeoForge-1.20.6-${project.version}.jar")
        // Pull the generated refmap into the jar root.
        from(layout.buildDirectory.dir("refmap"))
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
