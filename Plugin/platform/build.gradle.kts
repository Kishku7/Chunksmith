repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly(project(":chunksmith-common"))
    // folia-api is a superset of the Paper API surface these shims use, so both the Paper
    // and Folia helpers compile here against one classpath (verified). Runtime detection
    // (Paper.isPaper() / Folia.isFolia()) selects the right path on each server flavour.
    compileOnly(group = "dev.folia", name = "folia-api", version = "26.1.2.build.8-stable")
}