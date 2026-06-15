// mod/1.20.x backport build.
// common + nbt are Minecraft-FREE pure Java libraries (JDK 17, release 17).
// The fabric-1.20.1 variant applies fabric-loom itself and shades common+nbt.
// Per-module config lives in each module's own build.gradle.kts so loom can
// own the fabric module's java/jar tasks without interference.

subprojects {
    group = "${project.property("group")}"
    version = "${project.property("version")}"

    repositories {
        mavenCentral()
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
    }
}
