# Chunksmith — NeoForge 1.21.1

Standalone build for **Minecraft 1.21.1** (NeoForge).

## Build

```
cd NeoForge/1.21.1
./gradlew build
```

- Toolchain: ModDevGradle / NeoGradle, JDK 21.
- Output: `build/libs/Chunksmith-NeoForge-*.jar` (shaded).
- Shared `common/` + `nbt/` pulled from the branch root via relative include.
