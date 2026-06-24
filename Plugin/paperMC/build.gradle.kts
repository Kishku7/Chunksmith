repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly(project(":chunksmith-common"))
    // Latest available 26.2 Paper API (pre-release alpha; no stable 26.2 yet).
    compileOnly(group = "io.papermc.paper", name = "paper-api", version = "26.2-rc-2.build.9-alpha")
}