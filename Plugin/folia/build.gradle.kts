repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly(project(":chunksmith-common"))
    // Folia has no 26.2 build yet; compile the small Folia platform glue against the
    // latest stable 26.1.2 folia-api. The Folia API surface Chunksmith uses is stable
    // across 26.1 -> 26.2 and the jar is forward-compatible on a 26.2 Folia server.
    compileOnly(group = "dev.folia", name = "folia-api", version = "26.1.2.build.8-stable")
}