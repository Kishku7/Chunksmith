dependencies {
    compileOnly(group = "com.google.code.gson", name = "gson", version = "2.8.9")
    compileOnly(group = "org.slf4j", name = "slf4j-api", version = "2.0.16")
    testImplementation(group = "junit", name = "junit", version = "4.13.2")
    testImplementation(group = "com.google.code.gson", name = "gson", version = "2.10.1")
    // LodWarnings logs through slf4j, and the tests exercise it (a silent LOD failure is the bug those
    // tests exist to prevent). slf4j-api is compileOnly for the mod -- the loader supplies it at runtime --
    // but the JVM the tests run in has no loader, so it needs the api on the test classpath. With no
    // provider bound, slf4j 2 falls back to a no-op logger, which is exactly what a test wants.
    testImplementation(group = "org.slf4j", name = "slf4j-api", version = "2.0.16")
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-Xlint:all")
    }
    processResources {
        filesMatching("version.properties") {
            expand(
                "version" to project.version
            )
        }
    }
    javadoc {
        sourceSets {
            main {
                allJava
            }
        }
        setDestinationDir(rootProject.projectDir.resolve("docs/chunksmith/javadoc"))
        include("com/kishku7/chunksmith/api/**")
        exclude("com/kishku7/chunksmith/api/ChunksmithAPIImpl.java")
    }
}
