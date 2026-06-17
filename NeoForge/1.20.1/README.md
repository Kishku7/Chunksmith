# Chunksmith — NeoForge 1.20.1

Standalone build for **Minecraft 1.20.1** (NeoForge).

## Build

```
cd NeoForge/1.20.1
./gradlew build
```

- Toolchain: ModDevGradle / NeoGradle, JDK 17.
- Output: `build/libs/Chunksmith-NeoForge-*.jar` (shaded).
- Shared `common/` + `nbt/` pulled from the branch root via relative include.
