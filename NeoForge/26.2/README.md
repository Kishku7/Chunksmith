# Chunksmith — NeoForge 26.2

Standalone build for **Minecraft 26.2** (NeoForge).

## Build

```
cd NeoForge/26.2
./gradlew build
```

- Toolchain: ModDevGradle / NeoGradle, JDK 25.
- Output: `build/libs/Chunksmith-NeoForge-*.jar` (shaded).
- Shared `common/` + `nbt/` pulled from the branch root via relative include.
