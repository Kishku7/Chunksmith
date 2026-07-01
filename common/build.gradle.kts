plugins {
    id("java-library")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    compileOnly(group = "com.google.code.gson", name = "gson", version = "2.8.9")
    compileOnly(group = "org.slf4j", name = "slf4j-api", version = "2.0.16")
    testImplementation(group = "junit", name = "junit", version = "4.13.2")
    testImplementation(group = "com.google.code.gson", name = "gson", version = "2.10.1")
    api(project(":chunksmith-nbt"))
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(17)
        options.compilerArgs.add("-Xlint:all")
    }
    processResources {
        filesMatching("version.properties") {
            expand(
                "version" to project.version
            )
        }
    }
}
