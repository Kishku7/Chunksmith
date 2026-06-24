dependencies {
    compileOnly(group = "com.google.code.gson", name = "gson", version = "2.8.9")
    compileOnly(group = "org.slf4j", name = "slf4j-api", version = "2.0.16")
    testImplementation(group = "junit", name = "junit", version = "4.13.2")
    testImplementation(group = "com.google.code.gson", name = "gson", version = "2.10.1")
}

tasks {
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
        exclude("com/kishku7/chunksmith/api/ChunkyAPIImpl.java")
    }
}
